package org.example.seedancegenarate.agent.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.example.seedancegenarate.agent.generation.AgentDirectGenerationGateway;
import org.example.seedancegenarate.agent.model.AgentContext.ArtifactRef;
import org.example.seedancegenarate.agent.persistence.AgentStore;
import org.example.seedancegenarate.agent.runtime.AgentGenerationRuntime;
import org.example.seedancegenarate.dto.SendMessageRequest;
import org.example.seedancegenarate.service.ConversationMediaResolver.LocalFiles;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.example.seedancegenarate.exception.BusinessException;
import org.example.seedancegenarate.agent.persistence.AgentRows.Session;
import java.security.MessageDigest;
import java.util.*;

/** User-selected generation only: upload and quote outside the durable approval transaction. */
@Service
@RequiredArgsConstructor
public class AgentDirectApplication {
    private final AgentStore store;
    private final TransactionTemplate tx;
    private final AgentDirectGenerationGateway gateway;
    private final AgentGenerationRuntime generation;
    private final ObjectMapper json;
    public record Request(String clientMsgId,Long expectedWorkspaceVersion,String outputType,JsonNode input,
                          ArtifactRef source,List<SendMessageRequest.Attachment> attachments) {}

    public void submit(long user,long conversation,Request request,LocalFiles files) {
        if(request==null || request.expectedWorkspaceVersion()==null || request.expectedWorkspaceVersion()<0
                || !Set.of("IMAGE","VIDEO","AUDIO").contains(Objects.toString(request.outputType(),""))
                || request.input()==null || !request.input().isObject() || request.input().toString().length()>16000)
            throw BusinessException.badRequest("直接生成参数无效");
        String key=AgentApplication.text(request.clientMsgId(),64,"请求编号");
        if(!request.input().path("prompt").isTextual()) throw BusinessException.badRequest("请输入生成描述");
        String prompt=AgentApplication.text(request.input().path("prompt").asText(),4000,"生成描述");
        Session initial=store.owned(conversation,user,false); // Check ownership before reading/uploading any file.
        LocalFiles local=files==null?LocalFiles.none():files;
        validateFiles(request.attachments(),local);
        String hash=AgentApplication.hashValue(store,List.of("direct",request,fileHashes(local)));
        if(replayed(initial,key,hash)) return;
        checkCurrent(initial,request,false);
        List<SendMessageRequest.Attachment> refs=request.attachments()==null?List.of():request.attachments();
        var quote=gateway.prepareDirect(user,request.outputType(),request.input().deepCopy(),refs,local);
        if(quote==null || !"DIRECT".equals(quote.origin()) || !request.outputType().equals(quote.mediaType()))
            throw new IllegalStateException("直接生成报价身份无效");
        tx.executeWithoutResult(t->{
            Session s=store.owned(conversation,user,true);
            if(replayed(s,key,hash)) return;
            checkCurrent(s,request,true); // Upload/quote may have raced a new plan, reference, or turn.
            String turnId=store.newDirectTurn(s,prompt);
            var turn=store.lockedTurn(turnId);
            String callId=store.newDirectCall(turn,quote.inputSnapshot(),json.valueToTree(request.source()));
            store.message(s,turnId,"USER",prompt,json.valueToTree(List.of(Map.of("type","text","text",prompt))),key,hash);
            generation.awaitApproval(s,turn,store.call(callId),quote);
            store.touch(s);
        });
    }
    private boolean replayed(Session s,String key,String hash) {
        String previous=store.requestHash(s,key);
        if(previous==null) return false;
        if(!previous.equals(hash)) throw BusinessException.conflict("同一请求编号不能用于不同内容或参考文件");
        return true;
    }
    private void checkCurrent(Session s,Request request,boolean lock) {
        var recipe=store.recipes().latest(s);
        if(recipe!=null&&!Set.of("COMPLETED","CANCELLED").contains(recipe.status()))throw BusinessException.conflict("请先停止当前技能运行，再直接生成");
        var w=store.workspace(s);
        if(w.path("version").asLong()!=request.expectedWorkspaceVersion()) throw BusinessException.conflict("创作状态已有更新，请刷新后重试");
        var turn=lock?store.lockedTurn(s.activeTurnId()):store.turn(s.activeTurnId());
        if(turn!=null && (AgentApplication.busy(turn.status()) || "WAITING_USER".equals(turn.status())))
            throw BusinessException.conflict("请先完成或停止当前轮次，再直接生成");
        var ref=request.source();
        if(ref==null) return;
        AgentApplication.text(ref.artifactId(),64,"来源作品");
        if(ref.version()<1) throw BusinessException.badRequest("来源作品版本无效");
        var artifact=store.artifactVersion(s,ref.artifactId(),ref.version());
        if(ref.sceneId()!=null) {
            AgentApplication.text(ref.sceneId(),64,"分镜编号"); boolean found=false;
            if("STORYBOARD".equals(artifact.type()) && artifact.data()!=null)
                for(var scene:artifact.data().path("scenes")) if(ref.sceneId().equals(scene.path("sceneId").asText())) found=true;
            if(!found) throw BusinessException.badRequest("来源分镜不存在");
        }
    }
    private static final long FILE_LIMIT=50L*1024*1024, TOTAL_LIMIT=200L*1024*1024;
    private void validateFiles(List<SendMessageRequest.Attachment> refs,LocalFiles files) {
        int count=refs==null?0:refs.size(); long total=0;
        if(refs!=null) for(var ref:refs) {
            if(ref==null || !Set.of("image","video","audio").contains(Objects.toString(ref.type(),""))) throw BusinessException.badRequest("参考素材类型无效");
            AgentApplication.text(ref.url(),2048,"素材地址");
        }
        for(MultipartFile[] group:groups(files)) for(var file:group) {
            count++;
            if(file==null || file.isEmpty() || file.getSize()<1 || file.getSize()>FILE_LIMIT) throw BusinessException.badRequest("参考文件不能为空且每个不能超过50MiB");
            total+=file.getSize();
        }
        if(count>16 || total>TOTAL_LIMIT) throw BusinessException.badRequest("最多16个参考素材，本地文件总计不能超过200MiB");
        if(files.imageOrder()!=null && (files.imageOrder().size()>16 || files.imageOrder().stream().anyMatch(v->v==null || !Set.of("file","url").contains(v))))
            throw BusinessException.badRequest("图片顺序标记无效");
    }
    private List<MultipartFile[]> groups(LocalFiles files) {
        return List.of(files.images()==null?new MultipartFile[0]:files.images(),files.videos()==null?new MultipartFile[0]:files.videos(),files.audios()==null?new MultipartFile[0]:files.audios());
    }
    private Object fileHashes(LocalFiles files) {
        var groups=new ArrayList<Object>(); long remaining=TOTAL_LIMIT;
        for(var group:groups(files)) {
            var values=new ArrayList<Object>();
            for(var file:group) {
                try(var stream=file.getInputStream()) {
                    var digest=MessageDigest.getInstance("SHA-256"); byte[] buffer=new byte[32768]; int length; long read=0;
                    while((length=stream.read(buffer))!=-1) {
                        read+=length; remaining-=length;
                        if(read>FILE_LIMIT || remaining<0) throw BusinessException.badRequest("参考文件超过大小限制");
                        digest.update(buffer,0,length);
                    }
                    values.add(List.of(Objects.toString(file.getOriginalFilename(),""),Objects.toString(file.getContentType(),""),read,HexFormat.of().formatHex(digest.digest())));
                } catch(BusinessException e) { throw e; }
                catch(Exception e) { throw BusinessException.badRequest("无法读取参考文件，请重新选择"); }
            }
            groups.add(values);
        }
        groups.add(files.imageOrder()==null?List.of():files.imageOrder()); return groups;
    }
}
