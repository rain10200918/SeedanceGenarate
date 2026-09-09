package org.example.seedancegenarate.agent.application;

import org.example.seedancegenarate.agent.api.AgentViews;
import org.example.seedancegenarate.agent.persistence.AgentStore;
import org.example.seedancegenarate.exception.BusinessException;
import org.springframework.stereotype.Service;

/** Read-only artifact browsing; deliberately independent of snapshot/command processing. */
@Service
public class AgentArtifactQueryApplication {
    private final AgentStore store;
    private final org.example.seedancegenarate.agent.generation.AgentGenerationGateway generation;
    public AgentArtifactQueryApplication(AgentStore store){this(store,null);}
    @org.springframework.beans.factory.annotation.Autowired
    public AgentArtifactQueryApplication(AgentStore store,org.example.seedancegenarate.agent.generation.AgentGenerationGateway generation){
        this.store=store;this.generation=generation;
    }
    public record Media(String type,String approvalId,String taskId,String status,String outputType,
                        String mediaPath,boolean blocked,boolean expired,String message) {}
    /** Exact owned artifact first, then live task visibility; no snapshot or lifecycle writes. */
    public Media media(long user,long conversation,String artifactId,int version) {
        var artifact=version(user,conversation,artifactId,version);
        if(!java.util.Set.of("IMAGE","VIDEO","AUDIO").contains(artifact.type())||artifact.taskId()==null||artifact.taskId().isBlank())
            throw BusinessException.notFound("此作品版本没有可预览的媒体");
        if(generation==null)throw new BusinessException(503,"媒体读取暂不可用");
        var task=generation.read(user,artifact.taskId());
        if(!artifact.type().equals(task.mediaType()))throw BusinessException.notFound("此作品版本的媒体不可用");
        return new Media("task","",task.taskId(),task.status(),task.mediaType(),
                task.blocked()||task.expired()?null:task.mediaPath(),task.blocked(),task.expired(),task.message());
    }
    public AgentViews.ArtifactPage page(long user,long conversation,String beforeId,int limit,String artifactId) {
        if(conversation<1 || limit<1 || limit>50) throw BusinessException.badRequest("分页参数不合法");
        if(artifactId!=null) validArtifactId(artifactId);
        Long cursor=null;
        if(beforeId!=null) {
            if(beforeId.length()>19 || !beforeId.matches("[1-9][0-9]*")) throw BusinessException.badRequest("作品游标不合法");
            try { cursor=Long.valueOf(beforeId); }
            catch(NumberFormatException e) { throw BusinessException.badRequest("作品游标不合法"); }
        }
        return store.artifactPage(store.owned(conversation,user,false),cursor,limit,artifactId);
    }
    public AgentViews.Artifact version(long user,long conversation,String artifactId,int version) {
        if(conversation<1 || version<1) throw BusinessException.badRequest("作品版本参数不合法");
        validArtifactId(artifactId);
        return store.artifactVersion(store.owned(conversation,user,false),artifactId,version);
    }
    private void validArtifactId(String id) {
        if(id==null || id.isBlank() || id.length()>64) throw BusinessException.badRequest("作品编号不合法");
    }
}
