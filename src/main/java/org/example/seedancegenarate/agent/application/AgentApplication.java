package org.example.seedancegenarate.agent.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.example.seedancegenarate.agent.api.AgentViews;
import org.example.seedancegenarate.agent.model.AgentModelGateway;
import org.example.seedancegenarate.agent.persistence.AgentStore;
import org.example.seedancegenarate.agent.runtime.AgentRuntime;
import org.example.seedancegenarate.exception.BusinessException;
import org.example.seedancegenarate.service.AsyncJobService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import static org.example.seedancegenarate.agent.persistence.AgentRows.*;

/** User commands only. LLM and Skill execution never run on this HTTP call stack. */
@Service
@RequiredArgsConstructor
public class AgentApplication {
    private final AgentStore store;
    private final TransactionTemplate tx;
    private final AsyncJobService jobs;
    private final AgentModelGateway models;
    private final ObjectMapper json;
    private final AgentApprovalApplication approvalApp;
    private final org.example.seedancegenarate.agent.persistence.AgentApprovalStore approvals;
    private final AgentImageInputs images;
    public record Send(String clientMsgId,String content,String channel,List<String> imageAssetIds) {
        public Send(String clientMsgId,String content,String channel){this(clientMsgId,content,channel,null);}
    }
    public record Answer(String clientActionId,String optionId,Integer expectedVersion) {}

    public List<AgentViews.Conversation> list(long user) { return store.list(user); }
    public void delete(long user,long id) {
        tx.executeWithoutResult(transaction -> {
            Session s=store.ownedForLifecycle(id,user,true);
            store.recipes().stop(s,true);
            Turn t=store.lockedTurn(s.activeTurnId());
            if(t!=null && (busy(t.status()) || Set.of("WAITING_USER","SUSPENDED").contains(t.status()))) {
                approvals.cancel(t.id()); store.cancel(t);
            }
            store.archive(s);
        });
    }
    public AgentViews.Conversation create(long user,String title) {
        String normalized=title==null||title.isBlank()?"新的创作":text(title,128,"标题");
        return tx.execute(t -> {
            long id=store.create(user,title==null||title.isBlank()?null:normalized);
            return new AgentViews.Conversation(Long.toString(id),normalized,store.now().toString());
        });
    }
    public AgentViews.Snapshot snapshot(long user,long id) {
        return tx.execute(t -> snapshotLocked(store.owned(id,user,true)));
    }
    public long revision(long user,long id) { return store.owned(id,user,false).revision(); }
    private AgentViews.Snapshot snapshotLocked(Session s) {
        if(expireQuestion(s)) s=store.owned(s.conversationId(),s.userId(),true);
        Turn t=store.turn(s.activeTurnId());
        boolean direct=t!=null && store.isDirectTurn(t);
        var messages=store.messages(s);
        var workspace=store.workspace(s);
        projectImages(s.userId(),messages,workspace);
        approvalApp.project(s,messages);
        return new AgentViews.Snapshot(Long.toString(s.conversationId()),store.title(s),s.revision(),
                new AgentViews.State(s.goal()==null?"":s.goal(),s.summary()==null?"":s.summary(),workspace,store.recipes().view(store,s),
                        store.actionableError(s,t),t==null?null:json.valueToTree(store.videoCheckpoints().progress(t))),
                t==null?null:new AgentViews.Turn(t.id(),t.status(),direct?null:t.channel(),t.error(),direct?"DIRECT":"AGENT"),messages,store.artifacts(s));
    }
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay=60000)
    public void expireQuestions() {
        for(var candidate:store.expiredWaitingSessions()) {
            tx.executeWithoutResult(t -> expireQuestion(store.ownedForLifecycle(candidate.conversationId(),candidate.userId(),true)));
        }
    }
    private boolean expireQuestion(Session s) {
        Turn t=store.lockedTurn(s.activeTurnId());
        if(t==null || !"WAITING_USER".equals(t.status())) return false;
        var pending=store.pending(t.id());
        if(pending==null || pending.expires().isAfter(store.now())) return false;
        store.expireInteraction(pending.id());
        String reason="这个问题已过期，请重新发送你的想法";
        store.executionStatus(t.id(),"FAILED",reason);
        store.message(s,t.id(),"ASSISTANT",reason,json.valueToTree(List.of(Map.of("type","text","text",reason))),null,null);
        store.touch(s); return true;
    }
    public AgentViews.Snapshot send(long user,long id,Send request) {
        if(request==null) throw BusinessException.badRequest("消息不能为空");
        String key=text(request.clientMsgId(),64,"请求编号");
        String content=text(request.content(),4000,"消息");
        String channel=request.channel()==null?"":text(request.channel(),128,"AI 模型");
        // Preserve old text-only request hashes; explicit [] is a distinct clear-image command.
        String hash=request.imageAssetIds()==null?hash(List.of("message",content,channel)):hash(List.of("message",content,channel,request.imageAssetIds()));
        Session initial=store.owned(id,user,false);
        if(replayed(initial,key,hash)) return snapshot(user,id);
        List<String> imageIds=request.imageAssetIds()==null?imageIds(store.workspace(initial)):request.imageAssetIds();
        images.resolve(user,imageIds);
        Turn initialTurn=store.turn(initial.activeTurnId());
        boolean continuing=initialTurn!=null && Set.of("WAITING_USER","SUSPENDED").contains(initialTurn.status());
        // Slow/cache-backed channel validation outside any DB transaction.
        String selected=channel.isEmpty()?(continuing?initialTurn.channel():(imageIds.isEmpty()?models.defaultChannel():models.defaultImageChannel())):channel;
        models.requireChannel(selected);
        if(!imageIds.isEmpty())models.requireImageChannel(selected);
        String selectedBinding=models.channelBinding(selected);
        tx.executeWithoutResult(transaction -> {
            Session s=store.owned(id,user,true);
            if(replayed(s,key,hash)) return;
            if(s.revision()!=initial.revision()) throw BusinessException.conflict("对话已更新，请刷新后重新发送");
            Turn current=store.turn(s.activeTurnId());
            var recipe=store.recipes().latest(s);
            if(recipe!=null&&Set.of("WAITING_INPUT","WAITING_CONFIRMATION").contains(recipe.status()))throw BusinessException.conflict("请通过技能信息或作品确认面板完成当前步骤");
            if(recipe!=null&&Set.of("FAILED","SUSPENDED").contains(recipe.status()))throw BusinessException.conflict("请使用技能面板恢复或停止当前技能");
            if(current!=null && busy(current.status())) throw BusinessException.conflict("Agent 正在处理，请等待或先停止本轮");
            var refs=images.resolve(user,imageIds);
            var workspace=store.workspace(s);
            if(!imageIds(workspace).equals(imageIds)) {
                if(current!=null && "SUSPENDED".equals(current.status()))throw BusinessException.conflict("本轮已有暂停的执行内容，请先停止本轮，再更换图片");
                workspace.set("imageAssetIds",json.valueToTree(imageIds));
                workspace.put("version",workspace.path("version").asLong()+1);store.saveWorkspace(s,workspace);
            }
            String turn;
            if(current!=null && "WAITING_USER".equals(current.status())) {
                if(!current.channel().equals(selected)) throw BusinessException.conflict("请先完成或停止本轮，再切换 AI 模型");
                var interaction=store.pending(current.id());
                if(interaction==null) throw BusinessException.conflict("问题已处理，请刷新对话");
                store.answer(interaction,content);
                turn=resumeOrStart(s,current);
            } else if(current!=null && "SUSPENDED".equals(current.status())) {
                if(!current.channel().equals(selected)) throw BusinessException.conflict("请先停止本轮，再切换 AI 模型");
                turn=resumeOrStart(s,current);
            } else {turn=store.newTurn(s,selected,content);if(recipe!=null&&"WAITING_PLAN".equals(recipe.status())) {store.recipes().human(current);store.recipes().bind(s,store.turn(turn));}}
            var parts=json.createArrayNode();parts.addObject().put("type","text").put("text",content);
            for(var ref:refs)parts.addObject().put("type","image").put("assetId",ref.assetId()).put("url",ref.url());
            store.bindModel(store.turn(turn),selectedBinding);
            store.message(s,turn,"USER",content,parts,key,hash);
            if(!"SUSPENDED".equals(store.turn(turn).status()))enqueue(store.turn(turn)); store.touch(s);
        });
        return snapshot(user,id);
    }
    private static List<String> imageIds(com.fasterxml.jackson.databind.JsonNode workspace) {
        var ids=new ArrayList<String>();for(var id:workspace.path("imageAssetIds"))ids.add(id.asText());return ids;
    }
    private void projectImages(long user,List<AgentViews.Message> messages,com.fasterxml.jackson.databind.node.ObjectNode workspace) {
        var cache=new HashMap<String,com.fasterxml.jackson.databind.node.ObjectNode>();
        java.util.function.Function<String,com.fasterxml.jackson.databind.node.ObjectNode> project=id->cache.computeIfAbsent(id,key->{
            var image=json.createObjectNode().put("type","image").put("assetId",key).put("unavailable",true);
            try {var ref=images.resolve(user,List.of(key)).get(0);image.put("url",ref.url()).put("unavailable",false);}
            catch(BusinessException e) {/* Unavailable input is still visible, without its former URL. */}
            return image;
        });
        for(var message:messages)for(var part:message.parts()) {
            if(!"image".equals(part.path("type").asText()) || !(part instanceof com.fasterxml.jackson.databind.node.ObjectNode image))continue;
            String id=image.path("assetId").asText();image.removeAll();image.setAll(project.apply(id));
        }
        var current=workspace.putArray("imageInputs");for(String id:imageIds(workspace))current.add(project.apply(id).deepCopy());
    }
    public AgentViews.Snapshot answer(long user,long id,String interactionId,Answer request) {
        if(request==null || request.expectedVersion()==null || request.expectedVersion()<1) throw BusinessException.badRequest("缺少问题版本");
        String key=text(request.clientActionId(),64,"请求编号"), option=text(request.optionId(),64,"选项");
        String hash=hash(List.of("answer",interactionId,option,request.expectedVersion()));
        tx.executeWithoutResult(transaction -> {
            Session s=store.owned(id,user,true);
            if(replayed(s,key,hash)) return;
            Turn t=store.turn(s.activeTurnId());
            var i=store.interaction(interactionId);
            if(t==null || i==null || !i.turnId().equals(t.id()) || i.epoch()!=t.epoch()) throw BusinessException.notFound("问题不存在");
            if(!"WAITING_USER".equals(t.status()) || i.version()!=request.expectedVersion()) throw BusinessException.conflict("这个问题已处理，请刷新对话");
            String label=null;
            for(var o:store.read(i.options())) if(option.equals(o.path("id").asText())) label=o.path("label").asText();
            if(label==null) throw BusinessException.badRequest("请选择当前问题提供的选项");
            store.answer(i,label);
            String turn=resumeOrStart(s,t);
            store.message(s,turn,"USER",label,json.valueToTree(List.of(Map.of("type","text","text",label))),key,hash);
            enqueue(store.turn(turn)); store.touch(s);
        });
        return snapshot(user,id);
    }
    private String resumeOrStart(Session s,Turn t) {
        if(!store.batches().beforeStep(store,s,t))return t.id();
        store.resumeHuman(t); return t.id();
    }
    public AgentViews.Snapshot cancel(long user,long id,String expectedTurn) {
        text(expectedTurn,64,"当前轮次");
        tx.executeWithoutResult(transaction -> {
            var s=store.owned(id,user,true); var t=store.turn(s.activeTurnId());
            if(t!=null && t.id().equals(expectedTurn) && (busy(t.status()) || Set.of("WAITING_USER","SUSPENDED").contains(t.status()))) { store.recipes().stop(s,true);approvals.cancel(t.id()); store.cancel(t); store.touch(s); }
        });
        return snapshot(user,id);
    }
    private boolean replayed(Session s,String key,String hash) {
        String old=store.requestHash(s,key);
        if(old==null) return false;
        if(!old.equals(hash)) throw BusinessException.conflict("同一请求编号不能用于不同内容");
        return true;
    }
    private void enqueue(Turn t) { jobs.enqueue(AgentRuntime.STEP_JOB,AgentRuntime.jobKey(t),store.write(new AgentRuntime.Payload(t.id(),t.epoch(),t.step(),null))); }
    public static boolean busy(String status) { return Set.of("QUEUED","RUNNING","WAITING_SKILL","WAITING_RETRY","WAITING_APPROVAL","SUBMITTING","WAITING_TASK").contains(status); }
    public boolean hasMedia(long user,long conversation) { return approvals.hasTask(store.owned(conversation,user,false)); }
    public static String text(String text,int max,String label) {
        if(text==null || text.isBlank() || text.length()>max) throw BusinessException.badRequest(label+"不能为空且不能超过 "+max+" 字符");
        return text.trim();
    }
    private String hash(Object value) {
        return hashValue(store,value);
    }
    public static String hashValue(AgentStore store,Object value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(store.write(value).getBytes(StandardCharsets.UTF_8))); }
        catch(Exception e) { throw new IllegalStateException(e); }
    }
}
