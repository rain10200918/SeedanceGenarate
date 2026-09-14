package org.example.seedancegenarate.agent.application;

import java.util.*;
import java.net.URI;
import org.example.seedancegenarate.entity.UserAsset;
import org.example.seedancegenarate.exception.BusinessException;
import org.example.seedancegenarate.config.OssConfig;
import org.example.seedancegenarate.mapper.UserAssetMapper;
import org.example.seedancegenarate.service.AssetService;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/** Owned image input boundary; never accepts caller supplied URLs. */
@Component
@lombok.RequiredArgsConstructor
public class AgentImageInputs {
    private final AssetService assets;
    private final UserAssetMapper mapper;
    private final OssConfig oss;
    public record ImageRef(String assetId,String url) {}
    public ImageRef upload(long user,MultipartFile file) {
        validate(file); base();
        try { return checked(user,assets.uploadImage(user,null,file)); }
        catch(BusinessException e) { throw e; }
        catch(Exception e) { throw new BusinessException(503,"图片上传失败，请稍后重试"); }
    }
    public List<ImageRef> resolve(long user,List<String> ids) {
        if(ids==null || ids.isEmpty())return List.of();
        if(ids.size()>4 || new HashSet<>(ids).size()!=ids.size())throw bad("最多选择4张不同的图片");
        var result=new ArrayList<ImageRef>();
        for(String id:ids) {
            long value;
            try { if(id==null || !id.matches("[1-9][0-9]{0,18}"))throw new NumberFormatException();value=Long.parseLong(id); }
            catch(NumberFormatException e) { throw bad("图片编号无效"); }
            ImageRef ref=checked(user,mapper.selectById(value));
            if(!id.equals(ref.assetId()))throw BusinessException.forbidden("图片不存在或无权使用");
            result.add(ref);
        }
        return List.copyOf(result);
    }
    /** History display only: unavailable IDs are omitted, submission's four-image validation is unchanged. */
    public Map<String,ImageRef> project(long user,Collection<String> ids) {
        var values=new LinkedHashSet<Long>();
        for(String id:ids) {
            try {if(id!=null && id.matches("[1-9][0-9]{0,18}"))values.add(Long.parseLong(id));}
            catch(NumberFormatException ignored) { /* malformed historical reference remains unavailable */ }
        }
        var ordered=new ArrayList<>(values);var result=new HashMap<String,ImageRef>();
        for(int offset=0;offset<ordered.size();offset+=100) {
            var batch=ordered.subList(offset,Math.min(offset+100,ordered.size()));
            for(var asset:mapper.selectList(com.baomidou.mybatisplus.core.toolkit.Wrappers.<UserAsset>lambdaQuery()
                    .eq(UserAsset::getUserId,user).in(UserAsset::getId,batch))) {
                if(asset==null || !values.contains(asset.getId()))continue;
                try {var ref=checked(user,asset);result.put(ref.assetId(),ref);}
                catch(BusinessException ignored) { /* retain the unavailable placeholder, never the old URL */ }
            }
        }
        return result;
    }
    private ImageRef checked(long user,UserAsset a) {
        if(a==null || a.getId()==null || a.getId()<1 || !Objects.equals(a.getUserId(),user)
                || !"ACTIVE".equals(a.getStatus()) || !"IMAGE".equals(a.getType()))throw BusinessException.forbidden("图片不存在或无权使用");
        URI allowed=base();
        try {
            String value=a.getUrl();if(value==null || value.length()>4096 || !value.equals(value.trim()))throw new IllegalArgumentException();
            URI u=URI.create(value);String p=u.getPath(),prefix=allowed.getPath();
            if(!Objects.equals(u.getScheme(),allowed.getScheme()) || u.getHost()==null || !u.getHost().equalsIgnoreCase(allowed.getHost())
                    || port(u)!=port(allowed) || u.getUserInfo()!=null || u.getRawQuery()!=null || u.getRawFragment()!=null
                    || p==null || p.isBlank() || p.contains("\\") || p.contains("%") || !URI.create(p).normalize().getPath().equals(p)
                    || (prefix!=null && !prefix.isBlank() && !"/".equals(prefix) && !p.startsWith(prefix.endsWith("/")?prefix:prefix+"/")))throw new IllegalArgumentException();
            return new ImageRef(a.getId().toString(),value);
        } catch(IllegalArgumentException e) { throw BusinessException.forbidden("图片不属于本系统存储"); }
    }
    private URI base() {
        String domain=oss.getDomain();
        if(domain==null || domain.isBlank()) {
            if(oss.getBucketName()==null || oss.getBucketName().isBlank() || oss.getEndpoint()==null || oss.getEndpoint().isBlank())throw new BusinessException(503,"图片存储尚未配置");
            domain="https://"+oss.getBucketName()+"."+oss.getEndpoint().replaceFirst("^https?://","");
        }
        try {
            URI u=URI.create(domain.contains("://")?domain:"https://"+domain);
            if(u.getHost()==null || !Set.of("http","https").contains(u.getScheme()) || u.getUserInfo()!=null || u.getQuery()!=null || u.getFragment()!=null)throw new IllegalArgumentException();
            return u;
        } catch(IllegalArgumentException e) { throw new BusinessException(503,"图片存储配置无效"); }
    }
    private static int port(URI u) { return u.getPort()<0?("https".equals(u.getScheme())?443:80):u.getPort(); }
    private static void validate(MultipartFile file) {
        if(file==null || file.isEmpty() || file.getSize()>10L*1024*1024)throw bad("图片必须非空且不超过10MiB");
        String name=Objects.toString(file.getOriginalFilename(),"").toLowerCase(Locale.ROOT);
        String ext=name.contains(".")?name.substring(name.lastIndexOf('.')+1):"";
        String mime=Objects.toString(file.getContentType(),"").toLowerCase(Locale.ROOT);byte[] head;
        try(var in=file.getInputStream()){head=in.readNBytes(32);}catch(Exception e){throw bad("无法读取图片");}
        boolean valid=switch(mime) {
            case "image/png" -> ext.equals("png") && starts(head,0,137,80,78,71,13,10,26,10);
            case "image/jpeg" -> Set.of("jpg","jpeg").contains(ext) && starts(head,0,255,216,255);
            case "image/gif" -> ext.equals("gif") && (ascii(head,0,"GIF87a") || ascii(head,0,"GIF89a"));
            case "image/webp" -> ext.equals("webp") && ascii(head,0,"RIFF") && ascii(head,8,"WEBP");
            default -> false;
        };
        if(!valid)throw bad("图片扩展名、类型和内容不匹配，仅支持PNG、JPEG、WebP、GIF");
    }
    private static boolean ascii(byte[] b,int p,String text){return starts(b,p,text.chars().toArray());}
    private static boolean starts(byte[] b,int p,int... values){if(b.length<p+values.length)return false;for(int i=0;i<values.length;i++)if((b[p+i]&255)!=values[i])return false;return true;}
    private static BusinessException bad(String message){return BusinessException.badRequest(message);}
}
