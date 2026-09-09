package org.example.seedancegenarate.agent.generation;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.example.seedancegenarate.config.OssConfig;
import org.example.seedancegenarate.dto.SendMessageRequest.Attachment;
import org.example.seedancegenarate.engine.ModelSpec;
import org.example.seedancegenarate.entity.UserAsset;
import org.example.seedancegenarate.exception.BusinessException;
import org.example.seedancegenarate.mapper.UserAssetMapper;
import org.example.seedancegenarate.service.ConversationMediaResolver;
import org.example.seedancegenarate.service.ConversationMediaResolver.LocalFiles;
import org.springframework.web.multipart.MultipartFile;
import java.net.URI;
import java.util.*;

/** Only this boundary can turn owner-checked library refs or request uploads into canonical refs. */
final class DirectReferenceMedia {
    private final ConversationMediaResolver media; private final UserAssetMapper assets; private final OssConfig oss; private final ObjectMapper json;
    DirectReferenceMedia(ConversationMediaResolver media,UserAssetMapper assets,OssConfig oss,ObjectMapper json) { this.media=media;this.assets=assets;this.oss=oss;this.json=json; }
    ArrayNode prepare(long user,List<Attachment> attachments,LocalFiles files,ModelSpec model) {
        List<Attachment> refs=attachments==null?List.of():attachments; LocalFiles local=files==null?LocalFiles.none():files;
        Map<String,Long> library=new HashMap<>();int[] counts=new int[3];long total=0;
        if(refs.size()>16)throw bad("最多支持16个参考素材");
        for(Attachment ref:refs) {
            if(ref==null)throw bad("参考素材不能为空");int kind=kind(ref.type());String url=url(ref.url());counts[kind]++;
            UserAsset asset=assets.selectOne(Wrappers.<UserAsset>lambdaQuery().eq(UserAsset::getUserId,user).eq(UserAsset::getUrl,url).eq(UserAsset::getStatus,"ACTIVE").last("LIMIT 1"));
            requireAsset(asset,user,url,ref.type());library.put(ref.type()+"\n"+url,asset.getId());
        }
        MultipartFile[][] groups={local.images(),local.videos(),local.audios()};
        for(int k=0;k<3;k++)if(groups[k]!=null)for(MultipartFile file:groups[k]) {
            counts[k]++;if(file==null || file.isEmpty() || file.getSize()<1 || file.getSize()>50L*1024*1024)throw bad("参考文件必须非空且不超过50MiB");
            total+=file.getSize();if(total>200L*1024*1024)throw bad("参考文件总大小不能超过200MiB");
            String mime=file.getContentType();if(mime==null || !mimeAllowed(k,mime.toLowerCase(Locale.ROOT)))throw bad("参考文件格式与类型不匹配");
            validateHeader(file,mime.toLowerCase(Locale.ROOT));
        }
        checkCounts(counts,model);
        // Fail closed before upload, including local-only requests when storage has no configured origin.
        if(Arrays.stream(counts).sum()>0)base();
        var ordered=order(refs,local);
        List<Attachment> resolved;
        try { resolved=media.resolve(ordered.refs(),ordered.files()); }
        catch(RuntimeException e) { throw new BusinessException(503,"参考素材上传失败，请稍后重试"); }
        if(resolved==null || resolved.size()!=Arrays.stream(counts).sum())throw new BusinessException(503,"参考素材上传结果不完整");
        ArrayNode out=json.createArrayNode();int[] actual=new int[3];
        for(Attachment ref:resolved) {
            if(ref==null)throw new BusinessException(503,"参考素材上传结果无效");actual[kind(ref.type())]++;
            String u=url(ref.url());var node=out.addObject().put("type",ref.type()).put("url",u);
            Long assetId=library.get(ref.type()+"\n"+u);if(assetId!=null)node.put("assetId",assetId);
        }
        if(!Arrays.equals(counts,actual))throw new BusinessException(503,"参考素材上传类型不匹配");
        return out;
    }
    ArrayNode restore(long user,JsonNode refs,ModelSpec model) {
        if(refs==null || !refs.isArray() || refs.size()>16)throw bad("确认素材无效");
        ArrayNode out=json.createArrayNode();int[] counts=new int[3];
        for(JsonNode ref:refs) {
            if(!ref.isObject())throw bad("确认素材无效");
            ref.fieldNames().forEachRemaining(k->{if(!Set.of("type","url","assetId").contains(k))throw bad("确认素材无效");});
            if(!ref.path("type").isTextual() || !ref.path("url").isTextual())throw bad("确认素材无效");
            String type=ref.path("type").textValue(),u=url(ref.path("url").textValue());counts[kind(type)]++;
            if(ref.has("assetId")) {
                JsonNode id=ref.get("assetId");if(!id.isIntegralNumber() || !id.canConvertToLong() || id.longValue()<1)throw bad("确认素材无效");
                UserAsset asset=assets.selectById(id.longValue());requireAsset(asset,user,u,type);
                if(!Objects.equals(asset.getId(),id.longValue()))throw BusinessException.forbidden("参考素材不可用");
            }
            out.add(ref.deepCopy());
        }
        checkCounts(counts,model);return out;
    }
    private static void requireAsset(UserAsset a,long user,String url,String type) {
        if(a==null || a.getId()==null || a.getId()<1 || !Objects.equals(a.getUserId(),user) || !url.equals(a.getUrl())
                || !"ACTIVE".equals(a.getStatus()) || !type.toUpperCase(Locale.ROOT).equals(a.getType()))throw BusinessException.forbidden("参考素材不存在或无权使用");
    }
    private static int kind(String type) { if(type==null)throw bad("参考素材类型无效");return switch(type){case "image"->0;case "video"->1;case "audio"->2;default->throw bad("参考素材类型无效");}; }
    private static boolean mimeAllowed(int type,String mime) {
        return switch(type) {
            case 0 -> Set.of("image/jpeg","image/png","image/webp","image/gif").contains(mime);
            case 1 -> Set.of("video/mp4","video/webm","video/quicktime").contains(mime);
            default -> Set.of("audio/mpeg","audio/mp3","audio/wav","audio/x-wav","audio/aac","audio/ogg","audio/flac","audio/x-flac","audio/mp4","audio/x-m4a","audio/webm").contains(mime);
        };
    }
    private static void validateHeader(MultipartFile file,String mime) {
        String name=Objects.toString(file.getOriginalFilename(),"").toLowerCase(Locale.ROOT);
        String ext=name.contains(".")?name.substring(name.lastIndexOf('.')+1):"";
        byte[] head;
        try(var stream=file.getInputStream()) { head=stream.readNBytes(32); }
        catch(Exception e) { throw bad("无法读取参考文件"); }
        boolean valid=switch(mime) {
            case "image/png" -> ext.equals("png") && starts(head,0,0x89,0x50,0x4e,0x47,13,10,26,10);
            case "image/jpeg" -> Set.of("jpg","jpeg").contains(ext) && starts(head,0,255,216,255);
            case "image/gif" -> ext.equals("gif") && (ascii(head,0,"GIF87a") || ascii(head,0,"GIF89a"));
            case "image/webp" -> ext.equals("webp") && ascii(head,0,"RIFF") && ascii(head,8,"WEBP");
            case "video/mp4" -> ext.equals("mp4") && ascii(head,4,"ftyp");
            case "video/quicktime" -> ext.equals("mov") && (ascii(head,4,"ftyp") || ascii(head,4,"wide") || ascii(head,4,"moov"));
            case "video/webm","audio/webm" -> ext.equals("webm") && starts(head,0,0x1a,0x45,0xdf,0xa3);
            case "audio/mpeg","audio/mp3" -> ext.equals("mp3") && (ascii(head,0,"ID3") || head.length>=2 && (head[0]&255)==255 && (head[1]&0xe0)==0xe0);
            case "audio/wav","audio/x-wav" -> ext.equals("wav") && ascii(head,0,"RIFF") && ascii(head,8,"WAVE");
            case "audio/aac" -> ext.equals("aac") && (ascii(head,0,"ADIF") || head.length>=2 && (head[0]&255)==255 && (head[1]&0xf6)==0xf0);
            case "audio/ogg" -> Set.of("ogg","oga","opus").contains(ext) && ascii(head,0,"OggS");
            case "audio/flac","audio/x-flac" -> ext.equals("flac") && ascii(head,0,"fLaC");
            case "audio/mp4","audio/x-m4a" -> Set.of("m4a","mp4").contains(ext) && ascii(head,4,"ftyp");
            default -> false;
        };
        if(!valid)throw bad("参考文件扩展名、格式和内容不匹配");
    }
    private static boolean ascii(byte[] bytes,int offset,String text) {
        return starts(bytes,offset,text.chars().toArray());
    }
    private static boolean starts(byte[] bytes,int offset,int... values) {
        if(bytes.length<offset+values.length)return false;
        for(int i=0;i<values.length;i++)if((bytes[offset+i]&255)!=values[i])return false;
        return true;
    }
    private static void checkCounts(int[] n,ModelSpec m) {
        if(Arrays.stream(n).sum()>16 || n[0]>m.imageMax() || n[1]>m.videoMax() || n[2]>m.audioMax()
                || (m.needImages() && n[0]<Math.max(1,m.imageMin())) || (n[0]>0 && n[0]<m.imageMin())
                || (m.needImageOrVideo() && n[0]+n[1]==0))throw bad("参考素材数量不符合所选模型要求");
    }
    private record Ordered(List<Attachment> refs,LocalFiles files) {}
    private static Ordered order(List<Attachment> refs,LocalFiles files) {
        List<String> order=files.imageOrder();
        if(order==null)return new Ordered(refs,files);
        List<Attachment> images=refs.stream().filter(a->"image".equals(a.type())).toList();
        MultipartFile[] local=files.images()==null?new MultipartFile[0]:files.images();
        if(order.size()!=images.size()+local.length)throw bad("图片顺序必须完整覆盖参考图片");
        int f=0,u=0;
        for(String tag:order) {
            if("file".equals(tag))f++;
            else if("url".equals(tag))u++;
            else throw bad("图片顺序标记无效");
        }
        if(f!=local.length || u!=images.size())throw bad("图片顺序数量不匹配");
        return new Ordered(refs,files);
    }
    private URI base() {
        String domain=oss.getDomain();
        if(domain==null || domain.isBlank()) {
            if(oss.getBucketName()==null || oss.getBucketName().isBlank() || oss.getEndpoint()==null || oss.getEndpoint().isBlank())
                throw new BusinessException(503,"参考素材存储尚未配置");
            domain="https://"+oss.getBucketName()+"."+oss.getEndpoint().replaceFirst("^https?://","");
        }
        try {
            URI base=URI.create(domain.contains("://")?domain:"https://"+domain);
            if(base.getHost()==null || !Set.of("https","http").contains(base.getScheme()) || base.getUserInfo()!=null || base.getQuery()!=null || base.getFragment()!=null)
                throw new IllegalArgumentException();
            return base;
        } catch(IllegalArgumentException e) { throw new BusinessException(503,"参考素材存储配置无效"); }
    }
    private String url(String value) {
        if(value==null || value.isBlank() || value.length()>4096 || !value.equals(value.trim()))throw bad("参考素材地址无效");
        URI allowed=base();
        try {
            URI uri=URI.create(value);String path=uri.getPath(),prefix=allowed.getPath();
            if(!Objects.equals(uri.getScheme(),allowed.getScheme()) || uri.getHost()==null || !uri.getHost().equalsIgnoreCase(allowed.getHost())
                    || port(uri)!=port(allowed) || uri.getUserInfo()!=null || uri.getRawQuery()!=null || uri.getRawFragment()!=null
                    || path==null || path.isBlank() || path.contains("\\") || path.contains("%") || !URI.create(path).normalize().getPath().equals(path)
                    || (prefix!=null && !prefix.isBlank() && !"/".equals(prefix) && !path.startsWith(prefix.endsWith("/")?prefix:prefix+"/")))throw new IllegalArgumentException();
            return value;
        } catch(IllegalArgumentException e) { throw bad("参考素材地址必须来自本系统存储"); }
    }
    private static int port(URI uri) { return uri.getPort()<0?("https".equals(uri.getScheme())?443:80):uri.getPort(); }
    private static BusinessException bad(String message) { return BusinessException.badRequest(message); }
}
