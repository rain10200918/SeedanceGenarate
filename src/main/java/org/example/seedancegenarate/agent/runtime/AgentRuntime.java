package org.example.seedancegenarate.agent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.seedancegenarate.agent.model.*;
import org.example.seedancegenarate.agent.generation.AgentVideoPromptPreparation;
import org.example.seedancegenarate.agent.generation.VideoPreparationException;
import org.example.seedancegenarate.agent.persistence.AgentStore;
import org.example.seedancegenarate.agent.skill.*;
import org.example.seedancegenarate.entity.AsyncJob;
import org.example.seedancegenarate.exception.BusinessException;
import org.example.seedancegenarate.service.AsyncJobService;
import org.example.seedancegenarate.service.llm.LlmChannelException;
import org.example.seedancegenarate.agent.persistence.AgentModelRecoveryStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import static org.example.seedancegenarate.agent.persistence.AgentRows.*;

/** One durable step per lease. Paid skills only produce approvals here. */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentRuntime {
    public static final String STEP_JOB="AGENT_STEP", SKILL_JOB="AGENT_SKILL";
    public static final long LEASE_SECONDS=180;
    /** Legacy media-completion guard; decision slicing uses AgentRuntimeProperties. */
    @Deprecated public static final int MAX_STEPS=16;
    private final AgentStore store;
    private final AsyncJobService jobs;
    private final TransactionTemplate tx;
    private final AgentPlanner planner;
    private final SkillRegistry skills;
    private final ObjectMapper json;
    private final AgentGenerationRuntime generation;
    private final AgentDecisionDiagnostics diagnostics;
    private final AgentModelGateway models;
    @org.springframework.beans.factory.annotation.Autowired
    private AgentVideoPromptPreparation videoPrompts;
    public record Payload(String turnId,long epoch,int step,String callId) {}
    private record Work(Session session,Turn turn,Call call,AgentContext context) {}
    /** Provenance is created only around the actual per-scene model invocation, never around quote/precheck. */
    private static final class VideoPromptModelFailure extends RuntimeException {
        final int ordinal;final LlmChannelException failure;
        VideoPromptModelFailure(int ordinal,LlmChannelException failure) {super(null,failure);this.ordinal=ordinal;this.failure=failure;}
    }
    public static String jobKey(Turn t) { return t.id()+":"+t.epoch()+":"+t.step(); }

    public void execute(AsyncJob lease,boolean skillJob) {
        Payload p;
        try { p=json.readValue(lease.getPayload(),Payload.class); }
        catch(Exception e) { jobs.failAndRetry(lease,"Agent 作业参数无效"); return; }
        if(p==null || p.turnId()==null || p.step()<0 || skillJob!=(p.callId()!=null)) {
            jobs.failAndRetry(lease,"Agent 作业身份无效"); return;
        }
        AgentDecision receivedDecision=null;
        String decisionChannel=null;
        try {
            Work work=tx.execute(t -> begin(lease,p,skillJob));
            if(work==null) return;
            decisionChannel=work.turn().channel();
            if(skillJob) {
                CreativeSkill skill=skills.get(work.call().skillId());
                if(!skill.descriptor().version().equals(work.call().skillVersion())) throw BusinessException.conflict("当前技能版本暂不可用");
                var input=store.read(work.call().input());
                skill.validate(input);
                if(skill instanceof TaskSkill taskSkill) {
                    TaskQuote quote=taskSkill.quote(work.context(),input);
                    var batch=generation.prepareBatch(work.context(),quote);
                    if(videoPreparation(work))prepareVideoScene(lease,p,work,quote,batch);
                    else tx.executeWithoutResult(t -> finishQuote(lease,p,quote,batch));
                } else {
                    SkillResult result=skill.execute(work.context(),input); // external I/O: NO transaction
                    tx.executeWithoutResult(t -> finishSkill(lease,p,result));
                }
            } else {
                receivedDecision=store.plans().edits().decision(store,work.session(),work.turn());
                if(receivedDecision==null)receivedDecision=planner.decide(work.context()); // external I/O: NO transaction
                AgentDecision decision=receivedDecision;
                tx.executeWithoutResult(t -> finishDecision(lease,p,decision));
            }
        } catch(Exception e) {
            if(e instanceof org.springframework.dao.DataAccessException) throw e; // transaction/commit uncertainty: let the lease recover
            log.warn("Agent step failed: turn={}, step={}, job={}, phase={}, code={}, kind={}",p.turnId(),p.step(),lease.getId(),
                    skillJob?"SKILL_EXECUTION":"DECISION",errorCode(e),e.getClass().getSimpleName());
            if(e instanceof VideoPreparationException preparation)
                log.warn("Agent video preparation: turn={}, step={}, call={}, scene={}, code={}, validationCode={}, validationDetail={}, diagnosticId={}, detail={}",
                        p.turnId(),p.step(),p.callId(),preparation.sceneOrdinal(),preparation.code(),preparation.validationCode(),
                        preparation.validationDetail(),preparation.diagnosticId(),preparation.getMessage());
            if(e instanceof InvalidAgentDecisionException invalid) {
                log.warn("Agent decision validation: turn={}, step={}, detail={}",p.turnId(),p.step(),e.getMessage());
                diagnostics.record(p.turnId(),p.epoch(),p.step(),decisionChannel,invalid.getMessage(),
                        invalid.rawOutput()!=null?invalid.rawOutput():receivedDecision==null?null:receivedDecision.rawOutput());
            }
            tx.executeWithoutResult(t -> failed(lease,p,skillJob,e));
        }
    }
    private Work begin(AsyncJob lease,Payload p,boolean skillJob) {
        fence(lease);
        Work w=current(p,skillJob);
        if(w==null) { complete(lease); return null; }
        if(!store.plans().edits().beforeStep(store,w.session(),w.turn())) {complete(lease);return null;}
        if(!store.batches().beforeStep(store,w.session(),w.turn())) {complete(lease);return null;}
        var recovery=store.modelRecovery().get(w.turn(),w.call());
        if(videoPreparation(w)&&recovery==null) {
            String expected=store.videoCheckpoints().nextJobKey(w.call());
            if(expected!=null&&!expected.equals(lease.getBizKey())) {complete(lease);return null;}
        }
        if(recovery!=null && !Set.of("RUNNING","WAITING_RETRY").contains(recovery.status())
                || "WAITING_RETRY".equals(w.turn().status())&&recovery==null&&!searchOperation(w)) {complete(lease);return null;}
        if(recovery!=null && !Objects.equals(recovery.jobKey(),lease.getBizKey())) {complete(lease);return null;}
        if(recovery!=null && recovery.nextRetryAt()!=null && recovery.nextRetryAt().isAfter(store.now())) {
            // Only an invalid early claim can get here: preserve the durable job for lease reclamation.
            return null;
        }
        // Model/search operations establish a fresh per-attempt deadline below under durable budgets.
        // Queue downtime is not execution time; other Skills retain their existing deadline guard.
        if(expired(w.turn()) && recovery==null && !modelOperation(w) && !searchOperation(w)
                || !store.userExists(w.session().userId())) {
            terminalFailure(w,"本轮处理已超时或账号不可用，请重新发送"); complete(lease); return null;
        }
        if(recovery!=null && !store.modelRecovery().matches(store,w.session(),w.turn(),w.call())) {
            suspend(w,"执行上下文已改变，旧模型调用已暂停；请确认当前计划后恢复。");
            store.modelRecovery().finish(w.turn(),w.call(),"STALE","MODEL_CONTEXT_CHANGED");complete(lease);return null;
        }
        if(!skillJob&&store.completePlanIfReady(w.session(),w.turn())) {
            if("COMPLETED".equals(store.turn(w.turn().id()).status()))store.modelRecovery().finish(w.turn(),null,"SUCCEEDED",null);
            store.touch(w.session());complete(lease);return null;
        }
        if(recovery!=null && recovery.attempts()>=AgentModelRecoveryStore.MAX_ATTEMPTS) {
            modelSuspended(w,"MODEL_RETRY_EXHAUSTED","模型调用已达到3次尝试上限，计划和作品已保存；请检查模型服务后明确恢复。");
            complete(lease);return null;
        }
        if(!skillJob && store.decisionBudgetExhausted(w.turn())) {
            var continued=store.yieldToSystemContinue(w.session(),w.turn());
            if(continued.isPresent()) {
                Turn next=continued.get();
                jobs.enqueue(STEP_JOB,jobKey(next),store.write(new Payload(next.id(),next.epoch(),next.step(),null)));
                store.touch(w.session());
            }
            complete(lease); return null;
        }
        if(!store.recipes().beforeStep(store,w.session(),w.turn(),!skillJob)) {complete(lease);return null;}
        if(skillJob) store.recipes().validateCall(store,w.session(),w.turn(),skills.get(w.call().skillId()).descriptor(),store.read(w.call().input()));
        if(searchOperation(w)) {
            if(!store.search().begin(store,w.session(),w.turn(),w.call(),lease.getBizKey()))return null;
            store.renewModelDeadline(w.turn());
        }
        if(modelOperation(w)) {
            String binding=models.channelBinding(w.turn().channel());
            if(!binding.equals(store.bindModel(w.turn(),binding)))
                throw LlmChannelException.terminal("Agent model identity changed",null)
                        .classified("MODEL_CONFIGURATION_CHANGED",false,null,null,null,null);
            store.modelRecovery().begin(store,w.session(),w.turn(),w.call(),lease.getBizKey());
            store.renewModelDeadline(w.turn()); // a bounded attempt, not the total Plan wall clock
        }
        if(skillJob) {store.callStatus(w.call().id(),"RUNNING",null);store.executionStatus(w.turn().id(),"WAITING_SKILL",null);}
        else store.executionStatus(w.turn().id(),"RUNNING",null);
        store.touch(w.session());
        var modelRecovery=store.modelRecovery().get(w.turn(),w.call());
        return new Work(w.session(),w.turn(),w.call(),context(w.session(),w.turn(),w.call())
                .withOutputRepair(modelRecovery!=null && modelRecovery.truncationRepairs()>0));
    }
    private Work current(Payload p,boolean skillJob) {
        Turn initial=store.turn(p.turnId());
        if(initial==null) return null;
        Session initialSession=store.session(initial.sessionId());
        if(initialSession==null) return null;
        Session s=store.ownedForLifecycle(initialSession.conversationId(),initialSession.userId(),true);
        Turn t=store.lockedTurn(p.turnId());
        if(!t.id().equals(s.activeTurnId()) || t.epoch()!=p.epoch() || t.step()!=p.step()) return null;
        Call call=skillJob?store.call(p.callId()):null;
        if(skillJob) {
            if(!Set.of("WAITING_SKILL","WAITING_RETRY").contains(t.status()) || call==null || !call.turnId().equals(t.id()) || call.epoch()!=p.epoch()
                    || call.step()!=p.step() || !Set.of("READY","RUNNING","WAITING_RETRY").contains(call.status())
                    || !store.workspaceMatches(s,call)||!store.recipes().matches(t,store.callContext(call.id()).get("recipeRun"))) return null;
        } else if(!Set.of("QUEUED","RUNNING","WAITING_RETRY").contains(t.status())) return null;
        return new Work(s,t,call,null);
    }
    /** Read-only DB context projection also used by expiry-only quote revalidation; never invokes a model. */
    public AgentContext quoteContext(Session s,Turn t,Call call) { return context(s,t,call); }
    private AgentContext context(Session s,Turn t,Call call) {
        var history=new ArrayList<AgentContext.HistoryMessage>();
        for(var m:store.history(s)) history.add(new AgentContext.HistoryMessage(m.get("role"),m.get("text")));
        var workspace=store.workspace(s);
        var scene=call==null?store.plans().currentScene(s,t):null;
        if(call==null)workspace=store.workspace(s);
        var selectionNode=call==null?(scene==null?workspace.get("selection"):scene.get("sourceRef")):store.callContext(call.id()).get("sourceRef");
        var selection=reference(selectionNode);
        var source=selection==null?null:resolve(s,selection);
        var artifacts=new ArrayList<AgentContext.ArtifactContext>();
        if(source!=null) artifacts.add(new AgentContext.ArtifactContext(source.id(),source.version(),source.type(),source.title(),source.content(),source.data()));
        for(var a:store.artifacts(s)) {
            if(source==null || !source.id().equals(a.id()) || source.version()!=a.version())
                artifacts.add(new AgentContext.ArtifactContext(a.id(),a.version(),a.type(),a.title(),a.content(),a.data()));
        }
        var plan=json.createObjectNode();
        var planRef=reference(workspace.get("planRef"));
        if(planRef!=null) {
            var a=resolve(s,planRef);
            plan.put("artifactId",a.id()).put("version",a.version()).put("confirmed",workspace.path("planConfirmed").asBoolean());
            plan.set("data",a.data()); plan.set("steps",workspace.path("steps")); plan.set("currentStepId",workspace.path("currentStepId"));
            plan.set("_confirmedRepairs",store.repairs().bindings(store,s,workspace));
        }
        pinReferenceArtifact(s,artifacts,plan.path("data").get("referenceImage"));
        if(source!=null&&"PLAN".equals(source.type())&&source.data()!=null)pinReferenceArtifact(s,artifacts,source.data().get("referenceImage"));
        if(call!=null)pinReferenceArtifact(s,artifacts,store.read(call.input()).get("referenceImage"));
        String goal=plan.path("confirmed").asBoolean()?plan.path("data").path("goal").asText(s.goal()):s.goal();
        var recipe=store.recipes().context(t);
        if(call!=null && recipe!=null && store.recipes().validateCall(store,s,t,skills.get(call.skillId()).descriptor(),store.read(call.input())))
            ((com.fasterxml.jackson.databind.node.ObjectNode)recipe).set("validatedDerivedSource",json.valueToTree(selection));
        if(!plan.path("confirmed").asBoolean()&&recipe!=null&&recipe.hasNonNull("goal"))goal=recipe.path("goal").asText();
        var imageState=call==null?workspace:store.callContext(call.id());
        var imageIds=new ArrayList<String>();
        var storedImages=imageState.path("imageAssetIds");
        if(!storedImages.isMissingNode() && (!storedImages.isArray() || storedImages.size()>4))
            throw BusinessException.badRequest("图片引用状态无效，请移除后重新添加");
        for(var image:storedImages) {
            if(!image.isTextual())throw BusinessException.badRequest("图片引用状态无效，请移除后重新添加");
            imageIds.add(image.textValue());
        }
        return new AgentContext(s.userId(),s.id(),t.id(),t.channel(),goal,s.summary(),history,artifacts,t.step(),store.confirmedChoices(s),planRef==null?null:plan,selection,store.plans().observations(t),recipe).withImageAssetIds(imageIds).withModelBinding(store.modelBinding(t));
    }
    private void pinReferenceArtifact(Session s,java.util.List<AgentContext.ArtifactContext> artifacts,com.fasterxml.jackson.databind.JsonNode node) {
        var ref=reference(node);
        if(ref==null||artifacts.stream().anyMatch(a->a.id().equals(ref.artifactId())&&a.version()==ref.version()))return;
        org.example.seedancegenarate.agent.api.AgentViews.Artifact a;
        try {a=resolve(s,ref);}
        catch(BusinessException failure) {if(failure.getCode()==404)return;throw failure;}
        artifacts.add(0,new AgentContext.ArtifactContext(a.id(),a.version(),a.type(),a.title(),a.content(),a.data()));
    }
    private AgentContext.ArtifactRef reference(com.fasterxml.jackson.databind.JsonNode node) {
        if(node==null || node.isNull()) return null;
        if(!node.isObject()) throw BusinessException.badRequest("作品引用格式无效");
        node.fieldNames().forEachRemaining(k->{if(!Set.of("artifactId","version","sceneId").contains(k))throw BusinessException.badRequest("作品引用包含未知字段");});
        var id=node.path("artifactId"); var version=node.path("version"); var scene=node.get("sceneId");
        if(!id.isTextual() || id.asText().isBlank() || id.asText().length()>64 || !version.isIntegralNumber() || !version.canConvertToInt() || version.intValue()<1
                || (scene!=null && !scene.isNull() && (!scene.isTextual() || scene.asText().isBlank() || scene.asText().length()>64)))
            throw BusinessException.badRequest("作品引用缺少有效编号或版本");
        return new AgentContext.ArtifactRef(id.asText(),version.intValue(),scene==null || scene.isNull()?null:scene.asText());
    }
    private org.example.seedancegenarate.agent.api.AgentViews.Artifact resolve(Session s,AgentContext.ArtifactRef ref) {
        var a=store.artifactVersion(s,ref.artifactId(),ref.version());
        if(ref.sceneId()!=null) {
            if(!"STORYBOARD".equals(a.type()) || a.data()==null) throw BusinessException.badRequest("该作品不是分镜");
            boolean found=false;
            for(var scene:a.data().path("scenes")) if(ref.sceneId().equals(scene.path("sceneId").asText())) found=true;
            if(!found) throw BusinessException.notFound("分镜不存在，请重新选择");
        }
        return a;
    }
    private void finishDecision(AsyncJob lease,Payload p,AgentDecision d) {
        fence(lease); Work w=current(p,false);
        if(w==null) { complete(lease); return; }
        if(!modelResultCurrent(w)) {complete(lease);return;}
        if(expired(w.turn())) { terminalFailure(w,"本轮处理已超时，请重新发送"); complete(lease); return; }
        Session s=w.session(); Turn t=w.turn();
        store.decision(t,json.valueToTree(d));
        store.summary(s,d.summary());
        switch(d.type()) {
            case "RESPOND" -> {
                var workspace=store.workspace(s); // projected from persisted Plan steps, not the saved pointer
                var recipe=store.recipes().bound(t);
                if(workspace.hasNonNull("executionPlanId") && workspace.hasNonNull("currentStepId")
                        || recipe!=null && !"COMPLETED".equals(recipe.status()))
                    awaitUser(s,t,d.text(),List.of(),"计划尚有未完成步骤，当前等待你的回复。");
                else { say(s,t,d.text()); store.executionStatus(t.id(),"COMPLETED",null); }
            }
            case "COMPLETE" -> {
                store.recipes().validateComplete(t);
                var workspace=store.workspace(s);
                if(workspace.hasNonNull("executionPlanId") && workspace.hasNonNull("currentStepId"))
                    throw new InvalidAgentDecisionException("$.type: 当前计划仍有未完成步骤，不能COMPLETE；请选择CALL_SKILL推进，或ASK_USER澄清");
                say(s,t,d.text()); store.executionStatus(t.id(),"COMPLETED",null);
            }
            case "ASK_USER" -> {
                awaitUser(s,t,d.text(),d.options(),null);
            }
            case "CALL_SKILL" -> {
                var skill=skills.get(d.skillId()); skill.validate(d.input());
                boolean recipeDerivation=store.recipes().validateCall(store,s,t,skill.descriptor(),d.input());
                var workspace=store.workspace(s);
                var scene=store.plans().currentScene(s,t);
                if(scene!=null)workspace.set("selection",scene.get("sourceRef"));
                String blocked=null;
                if(reference(workspace.get("planRef"))!=null && !workspace.path("planConfirmed").asBoolean()
                        && !"PLAN".equals(skill.descriptor().resultType())) blocked="请先采用上方创作计划，或描述你希望调整的计划内容。";
                var sourceRef=reference(d.input().has("source")?d.input().get("source"):workspace.get("selection"));
                if(sourceRef!=null) {
                    var source=resolve(s,sourceRef);
                    if(skill instanceof TaskSkill && (!Set.of("SCRIPT","PROMPT","STORYBOARD").contains(source.type())
                            || "STORYBOARD".equals(source.type()) && sourceRef.sceneId()==null))
                        blocked="请先选择要生成画面的具体分镜，或选择脚本、提示词作为来源。当前不支持将图片或视频作为参考输入。";
                }
                if(blocked!=null) {
                    if(workspace.hasNonNull("executionPlanId")) suspend(w,blocked);
                    else { say(s,t,blocked); store.executionStatus(t.id(),"COMPLETED",null); }
                    break;
                }
                store.plans().validateCall(s,t,skill.descriptor(),d.input(),workspace,recipeDerivation);
                String call=store.newCall(t,d.skillId(),skill.descriptor().version(),d.input());
                var parts=json.createArrayNode();
                String activity="video-generation".equals(d.skillId())?"正在准备视频生成规格与提示词，完成后请确认费用。":d.text();
                if(activity!=null && !activity.isBlank()) parts.addObject().put("type","text").put("text",activity);
                parts.addObject().put("type","skill_call").put("skillCallId",call).put("skillId",d.skillId()).put("status","READY");
                store.message(s,t.id(),"ASSISTANT",activity,parts,null,null);
                store.executionStatus(t.id(),"WAITING_SKILL",null);
                jobs.enqueue(SKILL_JOB,call,store.write(new Payload(t.id(),t.epoch(),t.step(),call)));
            }
            default -> throw BusinessException.badRequest("AI 返回了不支持的动作");
        }
        store.modelRecovery().finish(t,null,"SUCCEEDED",null);
        store.touch(s); complete(lease);
    }
    private void awaitUser(Session s,Turn t,String text,List<AgentDecision.Option> options,String notice) {
        String interaction=store.newInteraction(t,text,json.valueToTree(options));
        var parts=json.createArrayNode().add(json.valueToTree(Map.of("type","choice","interactionId",interaction,
                "version",1,"status","PENDING","question",text,"options",options)));
        if(notice!=null) parts.addObject().put("type","text").put("text",notice);
        store.message(s,t.id(),"ASSISTANT",text,parts,null,null);
        store.executionStatus(t.id(),"WAITING_USER",null);
    }
    private void finishSkill(AsyncJob lease,Payload p,SkillResult result) {
        fence(lease); Work w=current(p,true);
        if(w==null) { complete(lease); return; }
        if(!modelResultCurrent(w)) {complete(lease);return;}
        if(expired(w.turn())) { terminalFailure(w,"本轮处理已超时，请重新发送"); complete(lease); return; }
        int contentLimit=result!=null&&"PLAN".equals(result.type())?PlanGenerationSkill.MAX_DOCUMENT_CHARS:16000;
        if(result==null || !Set.of("SCRIPT","PROMPT","PLAN","STORYBOARD","WEB_RESEARCH").contains(result.type()) || result.content()==null
                || result.content().isBlank() || result.content().length()>contentLimit || result.title()==null || result.title().length()>128)
            throw BusinessException.badRequest("技能返回的作品无效");
        String declared=skills.get(w.call().skillId()).descriptor().resultType();
        if(declared!=null && !declared.equals(result.type())) throw BusinessException.badRequest("技能返回的作品类型不匹配");
        int dataLimit="PLAN".equals(result.type())?PlanGenerationSkill.MAX_DOCUMENT_CHARS:20000;
        if(Set.of("PLAN","STORYBOARD").contains(result.type()) && (result.data()==null || !result.data().isObject() || result.data().toString().length()>dataLimit))
            throw BusinessException.badRequest("结构化作品无效或超出长度限制");
        if("WEB_RESEARCH".equals(result.type()))WebSearchSkill.validateResult(result);
        validateResultSource(w,result);
        var a=store.recordResult(w.session(),w.call(),result);
        boolean unchanged="STORYBOARD".equals(result.type())&&result.source()!=null
                &&a.id().equals(result.artifactId())&&a.version()==result.source().version();
        store.recipes().result(store,w.session(),w.turn(),w.call(),a);
        store.callStatus(w.call().id(),"SUCCEEDED",null);
        if(searchOperation(w))store.search().finish(w.call(),"SUCCEEDED",null);
        store.modelRecovery().finish(w.turn(),w.call(),"SUCCEEDED",null);
        boolean insufficient="WEB_RESEARCH".equals(result.type())&&"INSUFFICIENT_EVIDENCE".equals(result.data().path("status").asText());
        store.plans().observe(w.turn(),w.call(),"SKILL_RESULT",insufficient?"INSUFFICIENT_EVIDENCE":unchanged?"UNCHANGED":"SUCCEEDED","artifactId="+a.id()+", version="+a.version()+", type="+a.type()+(unchanged?"；返回内容与原版本完全相同，未创建新版本。请核对用户要求，不要宣称已完成未发生的修改或再次重复调用。":""));
        var part=json.createObjectNode().put("type","artifact").put("artifactId",a.id()).put("version",a.version())
                .put("title",a.title()).put("content",a.content()).put("artifactType",a.type()).put("stepId",a.stepId());
        part.set("data",a.data());part.set("sourceRef",json.valueToTree(a.sourceRef()));part.set("planRef",json.valueToTree(a.planRef()));
        store.message(w.session(),w.turn().id(),"ASSISTANT",null,json.createArrayNode().add(part),null,null);
        var workspace=store.workspace(w.session());
        if(insufficient)suspend(w,"未找到符合限制的公开资料，研究进度已保存。请补充公开来源或调整检索要求；系统不会编造事实或自动放宽限制。");
        else if("PLAN".equals(result.type())) store.executionStatus(w.turn().id(),"COMPLETED",null);
        else if(workspace.path("planConfirmed").asBoolean() && !workspace.hasNonNull("executionPlanId"))
            store.executionStatus(w.turn().id(),"COMPLETED",null); // legacy consent is not authorization for autonomous continuation
        else {
            store.advance(w.session(),w.turn()).ifPresent(next->
                    jobs.enqueue(STEP_JOB,jobKey(next),store.write(new Payload(next.id(),next.epoch(),next.step(),null))));
        }
        store.touch(w.session()); complete(lease);
    }
    /** Verify plugin output against the accepted command, not the plugin's claimed provenance. */
    private void validateResultSource(Work w,SkillResult result) {
        if(store.workspace(w.session()).hasNonNull("executionPlanId") && result.artifactId()!=null
                && !store.plans().edits().permits(store.callContext(w.call().id()),json.valueToTree(result.source())))
            throw BusinessException.badRequest("自动计划不得覆盖既有作品，请停止计划后按准确版本修改");
        var expected=reference(store.callContext(w.call().id()).get("sourceRef"));
        var input=store.read(w.call().input());
        var actual=result.source();
        var source=actual==null?null:resolve(w.session(),actual);
        if(input.hasNonNull("artifactId")) {
            if(actual==null || !input.path("artifactId").asText().equals(actual.artifactId()) || actual.sceneId()!=null)
                throw BusinessException.badRequest("技能结果与指定作品不一致");
        } else {
            // Starting a new plan may intentionally ignore a selected non-plan artifact.
            boolean newPlan="PLAN".equals(result.type()) && actual==null && result.artifactId()==null
                    && !input.has("source") && (expected==null || !"PLAN".equals(resolve(w.session(),expected).type()));
            if(!newPlan && !Objects.equals(expected,actual)) throw BusinessException.badRequest("技能结果与已确认的来源版本不一致");
        }
        if(result.artifactId()!=null && (source==null || !source.id().equals(result.artifactId()) || !source.type().equals(result.type())))
            throw BusinessException.badRequest("技能不能跨作品类型覆盖原稿");
        if(!"STORYBOARD".equals(result.type())) return;
        if(source==null) throw BusinessException.badRequest("分镜必须有明确脚本或分镜来源");
        var scenes=result.data().path("scenes");
        if(!scenes.isArray() || scenes.isEmpty() || scenes.size()>12) throw BusinessException.badRequest("分镜数量无效");
        var ids=new HashSet<String>();
        for(var scene:scenes) if(!scene.isObject() || !scene.path("sceneId").isTextual() || scene.path("sceneId").asText().isBlank()
                || !ids.add(scene.path("sceneId").asText())) throw BusinessException.badRequest("分镜场景编号无效");
        if("SCRIPT".equals(source.type()) && result.artifactId()==null && actual.sceneId()==null) return;
        if(!"STORYBOARD".equals(source.type()) || actual.sceneId()==null || !source.id().equals(result.artifactId()) || !source.title().equals(result.title())
                || source.data()==null || source.data().path("scenes").size()!=scenes.size())
            throw BusinessException.badRequest("修改分镜必须指定准确场景");
        var before=source.data().path("scenes");
        for(int i=0;i<scenes.size();i++) {
            var oldScene=before.get(i); var newScene=scenes.get(i);
            if(!oldScene.path("sceneId").equals(newScene.path("sceneId"))
                    || !actual.sceneId().equals(oldScene.path("sceneId").asText()) && !oldScene.equals(newScene))
                throw BusinessException.badRequest("技能不得改动未选中的分镜场景");
        }
    }
    private void prepareVideoScene(AsyncJob lease,Payload p,Work work,TaskQuote quote,AgentBatchRuntime.Prepared batch) {
        var legacy=videoPrompts.preparePlan(work.context(),quote,batch);
        record VideoPlanState(AgentVideoPromptPreparation.Plan plan,java.util.Map<String,String> saved) {}
        var state=tx.execute(t -> {
            fence(lease);Work current=current(p,true);
            if(current==null||!modelResultCurrent(current)){complete(lease);return null;}
            var plan=store.videoCheckpoints().usesLegacyBinding(current.session(),current.turn(),current.call(),legacy)
                    ?legacy:videoPrompts.structuredPlan(legacy);
            var result=store.videoCheckpoints().loadOrCreate(current.session(),current.turn(),current.call(),plan);
            store.touch(current.session());return new VideoPlanState(plan,result);
        });
        if(state==null)return;
        var plan=state.plan();var saved=state.saved();
        var scene=plan.scenes().stream().filter(item->!saved.containsKey(item.key())).findFirst().orElse(null);
        if(scene==null) {
            var prepared=videoPrompts.assemble(plan,saved);
            tx.executeWithoutResult(t->finishQuote(lease,p,prepared.first(),prepared.batch()));return;
        }
        String prompt;
        String repairHint=store.videoCheckpoints().repairHint(work.call(),scene);
        var recovery=store.modelRecovery().get(work.turn(),work.call());
        log.info("Agent video prompt scene started: turn={}, step={}, call={}, scene={}, completed={}, total={}, attempt={}, truncationRepairs={}",
                work.turn().id(),work.turn().step(),work.call().id(),scene.ordinal(),saved.size(),plan.scenes().size(),
                recovery==null?0:recovery.attempts(),recovery==null?0:recovery.truncationRepairs());
        try {prompt=repairHint==null?videoPrompts.prepareScene(work.context(),plan,scene)
                :videoPrompts.prepareScene(work.context(),plan,scene,repairHint);}
        catch(LlmChannelException error){throw new VideoPromptModelFailure(scene.ordinal(),error);}
        catch(VideoPreparationException error){throw error.atScene(scene.ordinal()).withSource(scene.source());}
        String result=prompt;
        tx.executeWithoutResult(t->{
            fence(lease);Work current=current(p,true);
            if(current==null){complete(lease);return;}
            if(!modelResultCurrent(current)){complete(lease);return;}
            if(expired(current.turn())){terminalFailure(current,"本次提示词准备已超时，已保存的结果保留。");complete(lease);return;}
            store.videoCheckpoints().save(current.session(),current.turn(),current.call(),plan,scene,result);
            saved.put(scene.key(),result);
            log.info("Agent video prompt scene validated: turn={}, step={}, call={}, scene={}, completed={}, total={}, promptChars={}",
                    current.turn().id(),current.turn().step(),current.call().id(),scene.ordinal(),saved.size(),plan.scenes().size(),result.length());
            if(saved.size()==plan.scenes().size()) {
                var prepared=videoPrompts.assemble(plan,saved);
                finishQuote(lease,p,prepared.first(),prepared.batch());
            } else {
                store.modelRecovery().nextPreparedScene(current.turn(),current.call());
                jobs.enqueue(SKILL_JOB,store.videoCheckpoints().nextJobKey(current.call()),store.write(p));
                store.touch(current.session());complete(lease);
            }
        });
    }
    private void finishQuote(AsyncJob lease,Payload p,TaskQuote quote,AgentBatchRuntime.Prepared batch) {
        fence(lease); Work w=current(p,true);
        if(w==null) { complete(lease); return; }
        if(expired(w.turn())) { terminalFailure(w,"本轮处理已超时，请重新发送"); complete(lease); return; }
        if(!modelResultCurrent(w)) {complete(lease);return;}
        if(batch==null)generation.awaitApproval(w.session(),w.turn(),w.call(),quote);
        else store.batches().create(store,w.session(),w.turn(),w.call(),batch);
        store.modelRecovery().finish(w.turn(),w.call(),"SUCCEEDED",null);
        store.touch(w.session()); complete(lease);
    }
    private void failed(AsyncJob lease,Payload p,boolean skillJob,Exception error) {
        fence(lease); Work w=current(p,skillJob);
        if(w==null) { complete(lease); return; }
        if(!modelResultCurrent(w)) {complete(lease);return;}
        if(error instanceof SearchProvider.Failure searchError) {
            recoverSearch(lease,p,w,searchError);return;
        }
        if(error instanceof VideoPromptModelFailure sceneError) {
            store.plans().observeModelError(w.turn(),w.call(),sceneError.failure.code());
            recoverVideoPrompt(lease,p,w,sceneError.failure,store.modelRecovery().get(w.turn(),w.call()),sceneError.ordinal);return;
        }
        if(error instanceof LlmChannelException modelError) {
            recoverModel(lease,p,w,modelError);return;
        }
        String code=errorCode(error);
        if(error instanceof org.example.seedancegenarate.agent.skill.SkillOutputContractException output && w.call()!=null
                && Set.of("plan-generation","script-generation","storyboard-generation","prompt-optimization").contains(w.call().skillId())
                && !(skills.get(w.call().skillId()) instanceof TaskSkill)) {
            var recovery=store.modelRecovery().get(w.turn(),w.call());
            store.plans().observeOutputRepair(w.turn(),w.call(),output.getMessage());
            if(recovery!=null && recovery.attempts()<AgentModelRecoveryStore.MAX_ATTEMPTS && !expired(w.turn())
                    && store.modelRecovery().reserveOutputRepair(w.turn(),w.call())) {
                String key=store.modelRecovery().defer(w.turn(),w.call(),code,1);
                store.callStatus(w.call().id(),"WAITING_RETRY",code);
                store.executionStatus(w.turn().id(),"WAITING_RETRY","文字作品格式未通过校验，正在自动修正一次；已有作品和计划已保留。");
                store.renewModelDeadline(w.turn());
                jobs.enqueueDelayed(SKILL_JOB,key,store.write(p),1);
            } else {
                store.modelRecovery().finish(w.turn(),w.call(),"SUSPENDED",code);
                store.callStatus(w.call().id(),"SUSPENDED",code);
                suspend(w,"文字作品格式仍未通过校验，自动修正已停止，任务已暂停；已有作品和计划已保留，尚未提交媒体任务。");
            }
            log.warn("Agent text output validation: turn={}, step={}, call={}, code={}, detail={}",w.turn().id(),w.turn().step(),w.call().id(),code,output.getMessage());
            store.touch(w.session());complete(lease);return;
        }
        if(error instanceof VideoPreparationException preparation && videoPreparation(w)
                && preparation.validationCode()!=null && preparation.sceneOrdinal()!=null) {
            store.videoCheckpoints().recordFailure(w.session(),w.turn(),w.call(),preparation);
            var recovery=store.modelRecovery().get(w.turn(),w.call());
            if("VIDEO_PROMPT_OUTPUT_INVALID".equals(code) && recovery!=null && recovery.attempts()<AgentModelRecoveryStore.MAX_ATTEMPTS && !expired(w.turn())
                    && store.videoCheckpoints().reserveRepair(w.session(),w.turn(),w.call(),preparation.sceneOrdinal())) {
                String key=store.modelRecovery().deferPreparedScene(w.turn(),w.call(),preparation.sceneOrdinal(),code,1);
                store.plans().observePreparationRepair(w.turn(),w.call(),preparation.sceneOrdinal());
                store.executionStatus(w.turn().id(),"WAITING_RETRY","正在修正第 "+preparation.sceneOrdinal()+" 幕视频提示词，已有进度保留，尚未提交生成任务。");
                store.callStatus(w.call().id(),"WAITING_RETRY",code);
                store.renewModelDeadline(w.turn());
                jobs.enqueueDelayed(SKILL_JOB,key,store.write(p),1);
                log.info("Agent video prompt repair scheduled: turn={}, call={}, scene={}, validationCode={}, diagnosticId={}, repair=1/1",
                        w.turn().id(),w.call().id(),preparation.sceneOrdinal(),preparation.validationCode(),preparation.diagnosticId());
                store.touch(w.session());complete(lease);return;
            }
        }
        if(error instanceof VideoPreparationException preparation)store.preparationError(w.session(),w.turn(),w.call(),preparation);
        boolean businessConflict=error instanceof BusinessException business&&business.getCode()==409;
        String detail=error instanceof VideoPreparationException?error.getMessage():businessConflict?"执行状态发生冲突，请检查当前计划、费用确认和引用来源后再操作。":error instanceof InvalidAgentDecisionException?error.getMessage():skillJob?
                "技能未完成，未产生可用作品。请检查技能输入、来源版本和能力；不能当作成功继续。":"模型调用未完成，请检查本轮模型通道或稍后重试。";
        store.plans().observe(w.turn(),w.call(),"ERROR",code,detail);
        store.modelRecovery().finish(w.turn(),w.call(),"FAILED",code);
        if(w.call()!=null) store.callStatus(w.call().id(),error instanceof VideoPreparationException?"SUSPENDED":"FAILED",code);
        boolean repair=error instanceof InvalidAgentDecisionException;
        if(repair && store.plans().failures(w.turn())<=2 && !expired(w.turn())) {
            store.continueTurn(w.turn().id()); Turn next=store.turn(w.turn().id());
            jobs.enqueue(STEP_JOB,jobKey(next),store.write(new Payload(next.id(),next.epoch(),next.step(),null)));
        } else if(repair) {
            suspend(w,"AI 决策未通过校验，自动修正已停止，任务已暂停。计划和已有作品已保留；可检查决策诊断后恢复原计划。");
        } else if(error instanceof VideoPreparationException preparation) {
            suspend(w,detail+("VIDEO_PROMPT_OUTPUT_INVALID".equals(code)
                    ?"提示词校验未通过，任务已暂停；请检查准备诊断后恢复，已通过的逐幕结果保留。"
                    :"当前进度已保留，未提交本次视频生成；请调整规格后恢复。"));
        } else if(businessConflict) {
            suspend(w,detail+"当前进度已保留，未自动重试或重新购买。");
        } else {
            terminalFailure(w,"本轮未能完成（"+code+"），未自动重试原任务。计划和已有作品已保留，请调整要求或检查服务后重试。");
        }
        store.touch(w.session()); complete(lease);
    }
    private void terminalFailure(Work w,String reason) {
        var recovery=store.modelRecovery().get(w.turn(),w.call());
        if(recovery!=null&&Set.of("RUNNING","WAITING_RETRY").contains(recovery.status()))
            store.modelRecovery().finish(w.turn(),w.call(),"FAILED","EXECUTION_FAILED");
        store.executionStatus(w.turn().id(),"FAILED",reason);
        if(w.call()!=null) store.callStatus(w.call().id(),"FAILED",reason);
        say(w.session(),w.turn(),reason); store.touch(w.session());
    }
    private boolean modelOperation(Work w) {
        return w.call()==null || videoPreparation(w) || Set.of("plan-generation","script-generation","storyboard-generation","prompt-optimization").contains(w.call().skillId())
                && !(skills.get(w.call().skillId()) instanceof TaskSkill);
    }
    // Only pre-approval text preparation. Generation submission lives in a different durable job.
    private boolean videoPreparation(Work w) {
        return videoPrompts!=null && w.call()!=null && "video-generation".equals(w.call().skillId())
                && skills.get(w.call().skillId()) instanceof TaskSkill;
    }
    private boolean searchOperation(Work w) {return w.call()!=null&&"web-search".equals(w.call().skillId());}
    private void recoverSearch(AsyncJob lease,Payload p,Work w,SearchProvider.Failure error) {
        var attempt=w.call()==null?null:store.search().get(w.call());
        store.search().observe(w.turn(),w.call(),error.code());
        if(searchOperation(w)&&error.retryable()&&attempt!=null&&store.search().used(attempt.scope())<3) {
            long delay=attempt.count()==1?15:30;
            String key=store.search().defer(w.call(),error.code(),delay);
            store.callStatus(w.call().id(),"WAITING_RETRY",error.code());
            store.executionStatus(w.turn().id(),"WAITING_RETRY","公开资料搜索暂时繁忙，正在自动重试，当前创作进度已保存。");
            store.renewModelDeadline(w.turn());
            jobs.enqueueDelayed(SKILL_JOB,key,store.write(p),delay);
        } else {
            if(w.call()!=null) {store.search().finish(w.call(),"SUSPENDED",error.code());store.callStatus(w.call().id(),"SUSPENDED",error.code());}
            suspend(w,"公开资料搜索已暂停，资料和创作进度已保存。请检查搜索服务配置或补充公开来源；不会自动放宽检索限制或重新购买媒体任务。");
        }
        store.touch(w.session());complete(lease);
    }
    private boolean modelResultCurrent(Work w) {
        if(store.modelRecovery().matches(store,w.session(),w.turn(),w.call())) return true;
        suspend(w,"执行上下文已改变，旧模型结果未被采用；请确认当前计划后恢复。");
        store.modelRecovery().finish(w.turn(),w.call(),"STALE","MODEL_CONTEXT_CHANGED");
        return false;
    }
    private void recoverModel(AsyncJob lease,Payload p,Work w,LlmChannelException error) {
        var recovery=store.modelRecovery().get(w.turn(),w.call());
        String code=error.code();
        store.plans().observeModelError(w.turn(),w.call(),code);
        if(videoPreparation(w)) {recoverVideoPrompt(lease,p,w,error,recovery,null);return;}
        boolean truncated="MODEL_OUTPUT_TRUNCATED".equals(code);
        boolean repair=truncated&&recovery!=null&&recovery.truncationRepairs()==0;
        if(modelOperation(w)&&recovery!=null&&(repair||!truncated&&error.retryable())&&recovery.attempts()<AgentModelRecoveryStore.MAX_ATTEMPTS) {
            long delay=recovery.attempts()==1?15:30;
            String key=store.modelRecovery().defer(w.turn(),w.call(),code,delay);
            var pending=store.modelRecovery().get(w.turn(),w.call());
            String reason=(repair?"模型输出达到额度，正在自动调整输出策略（仅修复一次）":"模型调用暂未完成（"+code+"）")
                    +"，已尝试 "+recovery.attempts()+"/3 次，预计 "+pending.nextRetryAt()+" 自动重试；计划和作品已保存。";
            store.executionStatus(w.turn().id(),"WAITING_RETRY",reason);
            if(w.call()!=null)store.callStatus(w.call().id(),"WAITING_RETRY",code);
            store.renewModelDeadline(w.turn());
            jobs.enqueueDelayed(w.call()==null?STEP_JOB:SKILL_JOB,key,store.write(p),delay);
            log.info("Agent model retry scheduled: turn={}, epoch={}, step={}, phase={}, call={}, attempt={}, maxAttempts=3, code={}, retryAt={}",
                    w.turn().id(),w.turn().epoch(),w.turn().step(),w.call()==null?"DECISION":"TEXT_SKILL",w.call()==null?null:w.call().id(),recovery.attempts(),code,pending.nextRetryAt());
        } else {
            String reason=!modelOperation(w)?"当前技能不允许自动重试":error.retryable()?"模型调用连续失败，自动尝试已停止":switch(code) {
                case "CONTEXT_BUILD_FAILED","MODEL_CONTEXT_OVERFLOW" -> "当前上下文准备失败或超过模型窗口，未原样重复请求";
                case "MODEL_AUTHENTICATION_FAILED","MODEL_NOT_FOUND","MODEL_INVALID_REQUEST" -> "模型配置或请求参数异常，未自动重复请求";
                case "MODEL_QUOTA_EXHAUSTED" -> "模型通道额度不足，未自动重复请求";
                case "MODEL_OUTPUT_TRUNCATED" -> "模型输出额度仍不足，已达到本次自动修复或总尝试上限，未采用截断结果";
                case "MODEL_OUTPUT_INVALID" -> "模型未返回完整有效结果，未原样重复请求";
                case "MODEL_VISION_UNSUPPORTED" -> "当前AI通道未配置图片理解能力，请联系管理员配置读图通道";
                case "MODEL_CONFIGURATION_CHANGED" -> "本轮模型或接口配置已改变，未切换模型继续执行；请恢复原配置后继续，或停止本轮后重新发起";
                case "IMAGE_INPUT_UNAVAILABLE" -> "引用图片已不可用或无权访问，请检查素材后重新添加";
                default -> "模型响应需要检查，未自动重复请求";
            };
            modelSuspended(w,code,reason+"（"+code+"）。任务已暂停，计划和作品已保存；请检查模型服务后明确恢复。");
        }
        store.touch(w.session());complete(lease);
    }
    /** Only the unfinished scene's text may be retried here; quote/submission/approval failures are not replayable. */
    private void recoverVideoPrompt(AsyncJob lease,Payload p,Work w,LlmChannelException error,AgentModelRecoveryStore.Recovery recovery,Integer ordinal) {
        var progress=store.videoCheckpoints().progress(w.turn());
        Integer currentOrdinal=progress==null?null:(Integer)progress.get("currentSceneOrdinal");
        boolean truncated="MODEL_OUTPUT_TRUNCATED".equals(error.code());
        log.warn("Agent video prompt model failure: turn={}, step={}, call={}, scene={}, attempt={}, truncationRepairs={}, code={}, httpStatus={}, promptTokens={}, completionTokens={}",
                w.turn().id(),w.turn().step(),w.call().id(),ordinal,recovery==null?0:recovery.attempts(),recovery==null?0:recovery.truncationRepairs(),
                error.code(),error.httpStatus(),error.promptTokens(),error.completionTokens());
        if(ordinal!=null && ordinal.equals(currentOrdinal) && truncated && recovery!=null && recovery.truncationRepairs()==0
                && recovery.attempts()<AgentModelRecoveryStore.MAX_ATTEMPTS && !expired(w.turn())) {
            long delay=recovery.attempts()==1?15:30;
            String key=store.modelRecovery().deferTruncatedScene(w.turn(),w.call(),ordinal,delay);
            store.callStatus(w.call().id(),"WAITING_RETRY",error.code());
            store.executionStatus(w.turn().id(),"WAITING_RETRY","第 "+ordinal+" 幕视频提示词输出达到上限，正在自动修复一次；已完成 "
                    +progress.get("completed")+" / "+progress.get("total")+" 幕，已有进度保留，尚未创建费用确认或生成任务。");
            store.renewModelDeadline(w.turn());
            jobs.enqueueDelayed(SKILL_JOB,key,store.write(p),delay);
        } else {
            String stage=ordinal==null?"视频生成规格检查":("第 "+ordinal+" 幕视频提示词准备");
            String reason=truncated?"输出达到上限，本次未能继续自动修复":"模型调用未完成";
            // A quote error must not appear as a failed scene-model invocation in the public progress projection.
            String code=ordinal==null&&truncated?"VIDEO_PREPARATION_PRECHECK_FAILED":error.code();
            modelSuspended(w,code,stage+"暂未完成："+reason+"（"+code+"）。任务已暂停，计划和已完成提示词已保存，"
                    +"尚未创建费用确认或生成任务；请检查模型服务后明确恢复。");
        }
        store.touch(w.session());complete(lease);
    }
    private void modelSuspended(Work w,String code,String reason) {
        store.modelRecovery().finish(w.turn(),w.call(),"SUSPENDED",code);
        if(w.call()!=null)store.callStatus(w.call().id(),"SUSPENDED",code);
        suspend(w,reason);
    }
    private void say(Session s,Turn t,String text) {
        store.message(s,t.id(),"ASSISTANT",text,json.valueToTree(List.of(Map.of("type","text","text",text))),null,null);
    }
    private String errorCode(Exception e) {
        if(e instanceof VideoPromptModelFailure scene)return scene.failure.code();
        if(e instanceof org.example.seedancegenarate.agent.skill.SkillOutputContractException) return org.example.seedancegenarate.agent.skill.SkillOutputContractException.CODE;
        if(e instanceof InvalidAgentDecisionException) return "INVALID_DECISION";
        if(e instanceof VideoPreparationException preparation) return preparation.code();
        if(e instanceof SearchProvider.Failure search) return search.code();
        if(e instanceof BusinessException b) return "BUSINESS_"+b.getCode();
        if(e instanceof LlmChannelException model) return model.code();
        return "EXECUTION_ERROR";
    }
    private void suspend(Work w,String reason) { store.executionStatus(w.turn().id(),"SUSPENDED",reason); say(w.session(),w.turn(),reason); store.touch(w.session()); }
    private boolean expired(Turn t) { return !t.deadline().isAfter(store.now()); }
    private void fence(AsyncJob lease) {
        if(!jobs.renew(lease,LEASE_SECONDS)) throw new IllegalStateException("Agent lease lost");
    }
    private void complete(AsyncJob lease) {
        if(!jobs.complete(lease)) throw new IllegalStateException("Agent lease lost on completion");
    }
}
