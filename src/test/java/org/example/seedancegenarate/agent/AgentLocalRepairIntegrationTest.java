package org.example.seedancegenarate.agent;

import org.example.seedancegenarate.agent.application.AgentRepairApplication;
import org.example.seedancegenarate.agent.generation.*;
import org.example.seedancegenarate.agent.model.*;
import org.example.seedancegenarate.agent.runtime.*;
import org.example.seedancegenarate.agent.skill.*;
import org.example.seedancegenarate.engine.*;
import org.example.seedancegenarate.service.*;
import org.example.seedancegenarate.exception.BusinessException;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AgentLocalRepairIntegrationTest extends AgentRuntimeIntegrationTest {
    AgentRepairApplication repairs;ModelAccessService access;VideoEngine engine;VideoSubmitService submit;
    AgentGenerationGateway gateway;AgentVideoReference refs;VideoEngineRegistry engines;
    @BeforeEach void repairSetup() throws Exception {
        String sql=new String(getClass().getResourceAsStream("/db/migration/V56__agent_local_repair.sql").readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)
                .replaceAll("(?i)\\bJSON\\b","TEXT").replaceAll("(?i)\\) ENGINE\\s*=.*?;", ");");
        new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(new org.springframework.core.io.ByteArrayResource(sql.getBytes(java.nio.charset.StandardCharsets.UTF_8))).execute(db.getDataSource());
        engine=mock(VideoEngine.class);when(engine.provider()).thenReturn("local");when(engine.models()).thenReturn(List.of(model("closed",8),model("open",15)));
        engines=new VideoEngineRegistry(List.of(engine));access=mock(ModelAccessService.class);when(access.isOpen("open")).thenReturn(true);
        submit=mock(VideoSubmitService.class);refs=mock(AgentVideoReference.class);
        gateway=new AgentGenerationGateway(engines,access,submit,mock(VideoTaskService.class),new ContentModerationPolicy(),new ArtifactExpiryPolicy(30),json,mock(AgentDirectGenerationGateway.class));
        org.springframework.test.util.ReflectionTestUtils.setField(gateway,"videoReference",refs);
        repairs=new AgentRepairApplication(store,tx,jobs,engines,access,gateway,refs,json);
    }
    ModelSpec model(String name,int max){return new ModelSpec("local",name,name,false,0,0,List.of("16:9"),5,max,List.of(),OutputType.VIDEO);}
    void storyboardFailure() {storyboardFailure(false);}
    void storyboardFailure(boolean reference) {
        tx.executeWithoutResult(txStatus->{
            var s=store.owned(id,1,true);var script=store.artifact(s,"script-fixture",null,"SCRIPT","剧本","猫回家");
            var data=json.createObjectNode();data.putObject("creationSpec").put("totalDurationSeconds",15).put("ratio","16:9");
            db.update("UPDATE agent_artifact_version SET data_json=? WHERE artifact_id=?",data.toString(),script.id());
            var p=store.artifact(s,"plan-fixture",null,"PLAN","计划","先分镜后视频");var pd=data.deepCopy().put("goal","猫回家");
            if(reference) {
                pd.putObject("referenceImage").put("artifactId","missing-image").put("version",1);
                store.artifact(s,"replacement-image",null,"IMAGE","有效参考","猫");
            }
            var steps=pd.putArray("steps");steps.addObject().put("id","script").put("kind","SCRIPT").put("title","脚本");
            steps.addObject().put("id","board").put("kind","STORYBOARD").put("title","分镜");
            steps.addObject().put("id","video").put("kind","VIDEO").put("title","视频").put("scope","STORYBOARD_SCENES").put("sourceStepId","board");
            db.update("UPDATE agent_artifact_version SET data_json=? WHERE artifact_id=?",pd.toString(),p.id());
            var w=store.workspace(s);w.put("planConfirmed",true);w.putObject("planRef").put("artifactId",p.id()).put("version",1);
            var sr=json.createObjectNode().put("artifactId",script.id()).put("version",1);w.set("selection",sr);
            w.withArray("steps").addObject().put("id","script").put("kind","SCRIPT").put("status","SUCCEEDED").set("artifactRef",sr);
            store.plans().adopt(s,store.artifactVersion(s,p.id(),1),w);store.saveWorkspace(s,w);
        });
        var storyboard=new StoryboardGenerationSkill(models,json,new StoryboardVideoCapabilities(gateway,engines,access,json));
        runtime=new AgentRuntime(store,jobs,tx,planner,new SkillRegistry(List.of(storyboard)),json,mock(AgentGenerationRuntime.class),diagnostics,models);
        when(planner.decide(any())).thenReturn(new AgentDecision("CALL_SKILL","分镜",null,List.of(),"storyboard-generation",
                json.createObjectNode().put("instruction","制作分镜").set("videoRequirements",json.createObjectNode().put("model","closed").put("duration",15))));
        when(models.complete(any(),eq("AGENT_STORYBOARD"),anyString(),anyString())).thenReturn("{\"title\":\"分镜\",\"scenes\":[{\"title\":\"猫\",\"visual\":\"猫回家\",\"narration\":\"\",\"duration\":15}]}");
        app.send(1,id,send("start","继续"));run();run();assertEquals("SUSPENDED",app.snapshot(1,id).turn().status());
    }
    AgentRepairApplication.Command command(String key) {
        var advice=repairs.suggestions(1,id);assertFalse(advice.path("options").isEmpty(),advice.toString());
        return new AgentRepairApplication.Command(key,advice.path("workspaceVersion").asLong(),advice.path("failureId").asText(),advice.path("options").get(0).path("optionId").asText());
    }
    // 【测什么】无分镜故障建议只指真实SCRIPT及步骤，确认后实际Runtime产出分镜且不重复SCRIPT。
    // 【怎么算红】不保存可信失败或不装载确认绑定，候选为空或恢复再次暂停。
    @Test void storyboardRepairHasNoInventedSceneAndResumesWithoutPayment() {
        storyboardFailure();var advice=repairs.suggestions(1,id);
        assertEquals("STORYBOARD",advice.path("target").path("kind").asText());
        assertFalse(advice.path("target").has("sceneOrdinal"));assertFalse(advice.path("target").path("sourceRef").hasNonNull("sceneId"));
        var c=command("confirm");repairs.confirm(1,id,c);repairs.confirm(1,id,c);
        assertEquals(1,count("agent_local_repair"));assertEquals(2,count("agent_skill_call"));assertEquals(0,count("agent_approval"));
        run();assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM agent_artifact_version WHERE type='STORYBOARD'",Integer.class));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM agent_artifact_version WHERE type='SCRIPT'",Integer.class));
        verifyNoInteractions(submit);
    }
    // 【测什么】真实三次超时→app.send继续的新call沿续准确授权；自动重试/旧job与跨plan/step/source无授权产物隔离。
    // 【怎么算红】仅绑定原call将阻断普通继续；删除绑定身份或来源校验会放行篡改来源的新call。
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"direct","wrongOrigin","automatic","resume","resume-plan","resume-step","resume-source","resume-unbound"})
    void storyboardRepairFlowsIntoVideoQuote(String path) throws Exception {
        when(engine.models()).thenReturn(List.of(new ModelSpec("local","open","新参考模型",true,1,1,List.of("16:9"),5,15,List.of(),OutputType.VIDEO).withImageInputMode(ModelSpec.ImageInputMode.REFERENCE_IMAGE)));
        when(refs.resolve(anyLong(),anyString(),any())).thenAnswer(a->{
            com.fasterxml.jackson.databind.JsonNode ref=a.getArgument(2);
            var image=store.artifactVersion(store.owned(id,1,false),ref.path("artifactId").asText(),ref.path("version").asInt());
            return json.createObjectNode().put("title",image.title()).put("mediaPath","/api/agent/media/ref").put("objectKey","key");
        });
        storyboardFailure(true);var advice=repairs.suggestions(1,id);var spec=advice.path("options").get(0).path("spec");
        var planBefore=db.queryForObject("SELECT data_json FROM agent_artifact_version WHERE type='PLAN'",String.class);
        repairs.confirm(1,id,command("board-reference"));
        String originalCall=store.read(db.queryForObject("SELECT binding_json FROM agent_local_repair",String.class)).path("restartCallId").asText();
        var originalJob=next();
        boolean resumed=path.startsWith("resume");
        String validBoard="{\"title\":\"分镜\",\"scenes\":[{\"title\":\"猫\",\"visual\":\"猫回家\",\"narration\":\"\",\"duration\":15}]}";
        if(resumed||"automatic".equals(path)) {
            doAnswer(a->{db.update("INSERT INTO job_probe(type,biz,payload) VALUES(?,?,?)",a.getArgument(0),a.getArgument(1),a.getArgument(2));return null;}).when(jobs).enqueueDelayed(anyString(),anyString(),anyString(),anyLong());
            when(models.complete(any(),eq("AGENT_STORYBOARD"),anyString(),anyString())).thenThrow(org.example.seedancegenarate.service.llm.LlmChannelException.readTimeout(new java.net.http.HttpTimeoutException("fake timeout")));
        }
        run();
        if("automatic".equals(path)) {
            assertEquals("WAITING_RETRY",app.snapshot(1,id).turn().status());
            doReturn(validBoard).when(models).complete(any(),eq("AGENT_STORYBOARD"),anyString(),anyString());
            db.update("UPDATE agent_model_recovery SET next_retry_at=TIMESTAMPADD(SECOND,-1,NOW()) WHERE status='WAITING_RETRY'");run();
            assertEquals(originalCall,db.queryForObject("SELECT source_call_id FROM agent_artifact_version WHERE type='STORYBOARD'",String.class));
        }
        if(resumed) {
            for(int attempt=0;attempt<2;attempt++) {
                assertEquals("WAITING_RETRY",app.snapshot(1,id).turn().status());
                db.update("UPDATE agent_model_recovery SET next_retry_at=TIMESTAMPADD(SECOND,-1,NOW()) WHERE status='WAITING_RETRY'");run();
            }
            assertEquals("SUSPENDED",app.snapshot(1,id).turn().status());
            doReturn("{\"title\":\"分镜\",\"scenes\":[{\"title\":\"猫\",\"visual\":\"猫回家\",\"narration\":\"\",\"duration\":15}]}").when(models).complete(any(),eq("AGENT_STORYBOARD"),anyString(),anyString());
            app.send(1,id,send("resume-board","继续"));run();run();
            assertEquals(1,count("agent_local_repair"));
            assertNotEquals(originalCall,db.queryForObject("SELECT source_call_id FROM agent_artifact_version WHERE type='STORYBOARD'",String.class));
        }
        var board=store.artifacts(store.owned(id,1,false)).stream().filter(a->"STORYBOARD".equals(a.type())).findFirst().orElseThrow();
        String producer=db.queryForObject("SELECT source_call_id FROM agent_artifact_version WHERE type='STORYBOARD'",String.class);
        runtime.execute(originalJob,true);
        assertEquals(producer,db.queryForObject("SELECT source_call_id FROM agent_artifact_version WHERE type='STORYBOARD'",String.class));
        assertEquals(originalCall,store.read(db.queryForObject("SELECT binding_json FROM agent_local_repair",String.class)).path("restartCallId").asText());
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM agent_artifact_version WHERE type='SCRIPT'",Integer.class));
        if(path.startsWith("resume-")) {
            var frozen=(com.fasterxml.jackson.databind.node.ObjectNode)store.callContext(producer);
            switch(path) {
                case "resume-plan" -> frozen.put("executionPlanId","another-plan");
                case "resume-step" -> frozen.put("currentStepId","another-step");
                case "resume-source" -> frozen.putObject("sourceRef").put("artifactId","another-script").put("version",1);
                case "resume-unbound" -> frozen.remove("confirmedStoryboardRepairId");
            }
            db.update("UPDATE agent_skill_call SET context_json=? WHERE id=?",frozen.toString(),producer);
        }
        assertEquals("open",board.data().path("videoCapabilities").path("model").asText());
        assertEquals(spec.path("referenceImage").path("artifactId"),board.data().path("videoCapabilities").path("referenceImage").path("artifactId"));
        if("wrongOrigin".equals(path))db.update("UPDATE agent_artifact_version SET source_call_id='unrelated-call' WHERE artifact_id=?",board.id());
        when(submit.estimate(anyString(),anyString(),anyInt())).thenAnswer(a->new VideoSubmitService.PriceEstimate(a.getArgument(0),a.getArgument(1),a.getArgument(2),"VIDEO",java.math.BigDecimal.ONE,java.math.BigDecimal.ONE,"CNY"));
        var gen=mock(AgentGenerationRuntime.class);when(gen.prepareBatch(any(),any())).thenAnswer(a->AgentBatchRuntime.prepare(gateway,a.getArgument(0),a.getArgument(1)));
        runtime=new AgentRuntime(store,jobs,tx,planner,new SkillRegistry(List.of(new VideoGenerationSkill(gateway))),json,gen,diagnostics,models);
        var preparation=spy(new AgentVideoPromptPreparation(gateway,models,new PromptTemplateService(),json));
        doReturn("猫沿着草地慢慢走回家，镜头平稳跟随。").when(preparation).prepareScene(any(),any(),any());
        org.springframework.test.util.ReflectionTestUtils.setField(runtime,"videoPrompts",preparation);
        when(planner.decide(any())).thenReturn(new AgentDecision("CALL_SKILL","视频",null,List.of(),"video-generation",json.createObjectNode().put("model","open").put("prompt","猫回家").put("duration",15)));
        run();run();
        if("wrongOrigin".equals(path)||path.startsWith("resume-")) {assertEquals("SUSPENDED",app.snapshot(1,id).turn().status());assertEquals(0,count("agent_approval"));return;}
        assertEquals("WAITING_APPROVAL",app.snapshot(1,id).turn().status());
        var quote=store.read(db.queryForObject("SELECT quote_json FROM agent_approval",String.class));
        assertEquals("open",quote.path("modelId").asText());
        assertEquals(spec.path("referenceImage").path("artifactId"),quote.path("inputSnapshot").path("referenceImage").path("artifactId"));
        assertEquals(planBefore,db.queryForObject("SELECT data_json FROM agent_artifact_version WHERE type='PLAN'",String.class));
        verify(submit,never()).submitApproved(any(),any());
    }
    // 【测什么】事务外建议有效但锁内模型下架或能力变更时，确认返回409且不写绑定。
    // 【怎么算红】只比较failure而不锁内重验access/ModelSpec，会错误创建修复。
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"closed","spec"})
    void modelChangesAtTransactionBoundaryAreRejected(String change) {
        storyboardFailure();var c=command("model-window");
        if("closed".equals(change))when(access.isOpen("open")).thenAnswer(a->!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
        else when(engine.models()).thenAnswer(a->List.of(model("closed",8),model("open",org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()?14:15)));
        assertEquals(409,assertThrows(BusinessException.class,()->repairs.confirm(1,id,c)).getCode());
        assertEquals(0,count("agent_local_repair"));
    }
    // 【测什么】同key异内容、旧版本、下架与跨用户确认均不创建绑定。
    // 【怎么算红】删除复验或所有权校验，绑定计数或409断言失败。
    @Test void ownershipStalenessAndReplayAreFenced() {
        storyboardFailure();var c=command("confirm");
        assertThrows(BusinessException.class,()->repairs.suggestions(2,id));
        when(access.isOpen("open")).thenReturn(false);
        assertEquals(409,assertThrows(BusinessException.class,()->repairs.confirm(1,id,c)).getCode());assertEquals(0,count("agent_local_repair"));
        when(access.isOpen("open")).thenReturn(true);repairs.confirm(1,id,c);
        var changed=new AgentRepairApplication.Command(c.clientActionId(),c.expectedWorkspaceVersion(),c.failureId(),"different");
        assertEquals(409,assertThrows(BusinessException.class,()->repairs.confirm(1,id,changed)).getCode());
        assertEquals(1,count("agent_local_repair"));
    }
    // 【测什么】无候选保留原秒数并给明确reason，候选最多12且GET不落库。
    // 【怎么算红】自动缩时或移除候选上限，数量/原因/零写入断言失败。
    @Test void noCandidateAndBoundedReadOnlyAdvice() {
        storyboardFailure();when(engine.models()).thenReturn(List.of(model("open",8)));
        var advice=repairs.suggestions(1,id);assertTrue(advice.path("options").isEmpty());assertFalse(advice.path("reason").asText().isBlank());
        when(engine.models()).thenReturn(java.util.stream.IntStream.range(0,20).mapToObj(i->model("m"+i,15)).toList());when(access.isOpen(anyString())).thenReturn(true);
        assertEquals(12,repairs.suggestions(1,id).path("options").size());assertEquals(0,count("agent_local_repair"));verifyNoInteractions(submit);
    }
    void videoFailure() {videoFailure(false);}
    void videoFailure(boolean referenceFailure) {videoFailure(referenceFailure,false);}
    void videoFailure(boolean referenceFailure,boolean firstPending) {videoFailure(referenceFailure,firstPending,null);}
    void videoFailure(boolean referenceFailure,boolean firstPending,String mode) {
        when(access.isOpen("closed")).thenReturn(true);
        if(referenceFailure) {
            when(engine.models()).thenReturn(List.of(new ModelSpec("local","closed","参考",true,1,1,List.of("16:9"),5,15,List.of(),OutputType.VIDEO).withImageInputMode(ModelSpec.ImageInputMode.REFERENCE_IMAGE)));
            when(refs.resolve(anyLong(),anyString(),any())).thenAnswer(a->{
                com.fasterxml.jackson.databind.JsonNode ref=a.getArgument(2);
                var image=store.artifactVersion(store.owned(id,1,false),ref.path("artifactId").asText(),ref.path("version").asInt());
                if(!"IMAGE".equals(image.type()))throw BusinessException.badRequest("图片不可用");
                return json.createObjectNode().put("title",image.title()).put("mediaPath","/api/agent/media/image-task").put("objectKey","stable-key");
            });
        }
        if(mode!=null)when(engine.models()).thenReturn(List.of(
                new ModelSpec("local","closed","首帧",true,1,1,List.of("16:9"),5,15,List.of(),OutputType.VIDEO).withImageInputMode(ModelSpec.ImageInputMode.FIRST_FRAME),
                new ModelSpec("local","open","角色参考",true,1,1,List.of("16:9"),5,15,List.of(),OutputType.VIDEO).withImageInputMode(ModelSpec.ImageInputMode.REFERENCE_IMAGE)));
        when(submit.estimate(anyString(),anyString(),anyInt())).thenAnswer(a->new VideoSubmitService.PriceEstimate(a.getArgument(0),a.getArgument(1),a.getArgument(2),"VIDEO",java.math.BigDecimal.ONE,java.math.BigDecimal.ONE,"CNY"));
        tx.executeWithoutResult(transaction->{
            var s=store.owned(id,1,true);var board=store.artifact(s,"board-fixture",null,"STORYBOARD","分镜","三幕");
            var data=json.createObjectNode();var scenes=data.putArray("scenes");
            for(int i=1;i<=3;i++)scenes.addObject().put("sceneId","s"+i).put("title","幕"+i).put("visual","猫走路").put("narration","").put("duration",i==(firstPending?2:3)?15:5);
            data.putObject("videoCapabilities").put("model","closed").put("ratio","16:9");
            if(referenceFailure&&mode==null) {
                ((com.fasterxml.jackson.databind.node.ObjectNode)data.path("videoCapabilities")).put("referenceMode","REFERENCE_IMAGE")
                        .putObject("referenceImage").put("artifactId","missing-image").put("version",1);
            }
            data.putObject("creationSpec").put("totalDurationSeconds",25).put("ratio","16:9");
            db.update("UPDATE agent_artifact_version SET data_json=? WHERE artifact_id=?",data.toString(),board.id());
            var w=store.workspace(s);store.plans().startScenes(store,s,store.artifactVersion(s,board.id(),1),"VIDEO",w);store.saveWorkspace(s,w);
            w=store.workspace(s);String step=w.path("steps").get(0).path("executionStepId").asText();
            var image=store.artifact(s,"image-fixture",null,"IMAGE","成功图片","已有图片");
            var imageRef=json.createObjectNode().put("artifactId",image.id()).put("version",1);
            db.update("UPDATE agent_plan_step SET ordinal_no=1 WHERE id=?",step);
            db.update("INSERT INTO agent_plan_step(id,plan_id,step_key,ordinal_no,kind,title,depends_on,status,result_ref) VALUES(?,?,?,0,'IMAGE','已有成功图片','[]','SUCCEEDED',?)","image-step",w.path("executionPlanId").asText(),"image",imageRef.toString());
            var video=store.artifact(s,"video-fixture",null,"VIDEO","成功视频","已有视频");
            db.update("UPDATE agent_plan_scene SET status='SUCCEEDED',result_ref=? WHERE plan_step_id=? AND scene_key='s1'",json.createObjectNode().put("artifactId",video.id()).put("version",1).toString(),step);
            if(referenceFailure)db.update("UPDATE agent_plan_scene SET status='SUCCEEDED',result_ref=? WHERE plan_step_id=? AND scene_key='s3'",json.createObjectNode().put("artifactId",video.id()).put("version",1).toString(),step);
        });
        var video=new VideoGenerationSkill(gateway);var gen=mock(AgentGenerationRuntime.class);
        when(gen.prepareBatch(any(),any())).thenAnswer(a->AgentBatchRuntime.prepare(gateway,a.getArgument(0),a.getArgument(1)));
        runtime=new AgentRuntime(store,jobs,tx,planner,new SkillRegistry(List.of(video)),json,gen,diagnostics,models);
        var preparation=spy(new AgentVideoPromptPreparation(gateway,models,new PromptTemplateService(),json));
        doReturn("猫沿着草地慢慢走回家，镜头平稳跟随。").when(preparation).prepareScene(any(),any(),any());
        org.springframework.test.util.ReflectionTestUtils.setField(runtime,"videoPrompts",preparation);
        var input=json.createObjectNode().put("model","closed").put("prompt","猫走路").put("duration",firstPending?15:5);
        if(mode!=null) {
            var image=store.artifacts(store.owned(id,1,false)).stream().filter(a->"IMAGE".equals(a.type())).findFirst().orElseThrow();
            input.putObject("referenceImage").put("artifactId","OMITTED".equals(mode)?image.id():"missing-image").put("version",1);
            if(!"OMITTED".equals(mode))input.put("referenceMode",mode);
        }
        when(planner.decide(any())).thenReturn(new AgentDecision("CALL_SKILL","视频",null,List.of(),"video-generation",input));
        app.send(1,id,send("start-video","继续"));run();run();
        assertEquals("SUSPENDED",app.snapshot(1,id).turn().status());
    }
    // 【测什么】真实Gateway失败→capture→建议沿用省略mode的REFERENCE_IMAGE语义，显式FIRST_FRAME不变。
    // 【怎么算红】按失败模型能力反推默认角色，省略mode将返回首帧候选或错误无候选。
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"OMITTED","FIRST_FRAME"})
    void repairPreservesGatewayReferenceRole(String mode) {
        videoFailure(true,false,mode);var advice=repairs.suggestions(1,id);
        assertEquals("VIDEO_REFERENCE_UNSUPPORTED",advice.path("code").asText());assertFalse(advice.path("options").isEmpty());
        String expected="OMITTED".equals(mode)?"REFERENCE_IMAGE":"FIRST_FRAME";
        for(var option:advice.path("options")) {
            assertEquals(expected,option.path("spec").path("referenceMode").asText());
            assertEquals("OMITTED".equals(mode)?"open":"closed",option.path("spec").path("model").asText());
        }
        assertEquals(0,count("agent_local_repair"));assertEquals(0,count("agent_approval"));
    }
    // 【测什么】仅第三幕换模型，成功IMAGE/VIDEO和第二幕原规格保留；旧作业不推进新绑定，新费用单仍PENDING。
    // 【怎么算红】恢复首幕规格复制或整板模型限制，恢复会再次失败；删除epoch fence会产生重复审批。
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void onlyFailedVideoChangesAndOldJobsCannotAdvanceIt(boolean firstPending) throws Exception {
        videoFailure(false,firstPending);var advice=repairs.suggestions(1,id);assertEquals("VIDEO_DURATION_UNSUPPORTED",advice.path("code").asText());
        assertEquals(firstPending?2:3,advice.path("target").path("sceneOrdinal").asInt());
        assertEquals(15,advice.path("options").get(0).path("spec").path("durationSeconds").asInt());
        var imageBefore=db.queryForMap("SELECT status,result_ref,skill_call_id FROM agent_plan_step WHERE kind='IMAGE'");
        var successBefore=db.queryForMap("SELECT status,result_ref,skill_call_id FROM agent_plan_scene WHERE scene_key='s1'");
        String oldCall=db.queryForObject("SELECT id FROM agent_skill_call",String.class);
        String oldContext=store.callContext(oldCall).toString();var oldTurn=store.turn(store.owned(id,1,false).activeTurnId());
        var oldJob=new org.example.seedancegenarate.entity.AsyncJob();oldJob.setId(-99L);oldJob.setBizKey(oldCall);
        oldJob.setPayload(store.write(new AgentRuntime.Payload(oldTurn.id(),oldTurn.epoch(),oldTurn.step(),oldCall)));
        var c=command("video-repair");repairs.confirm(1,id,c);runtime.execute(oldJob,true);
        assertEquals(oldContext,store.callContext(oldCall).toString());assertEquals(0,count("agent_approval"));
        run();run();
        assertEquals("WAITING_APPROVAL",app.snapshot(1,id).turn().status());assertEquals(2,count("agent_approval"));
        var quotes=db.query("SELECT quote_json FROM agent_approval ORDER BY created_at",(r,n)->store.read(r.getString(1)));
        assertEquals(Set.of("closed","open"),quotes.stream().map(q->q.path("modelId").asText()).collect(java.util.stream.Collectors.toSet()));
        assertEquals(Set.of(5,15),quotes.stream().map(q->q.path("inputSnapshot").path("duration").asInt()).collect(java.util.stream.Collectors.toSet()));
        assertEquals(Set.of("closed:5","open:15"),quotes.stream().map(q->q.path("modelId").asText()+":"+q.path("inputSnapshot").path("duration").asInt()).collect(java.util.stream.Collectors.toSet()));
        assertEquals(imageBefore,db.queryForMap("SELECT status,result_ref,skill_call_id FROM agent_plan_step WHERE kind='IMAGE'"));
        assertEquals(successBefore,db.queryForMap("SELECT status,result_ref,skill_call_id FROM agent_plan_scene WHERE scene_key='s1'"));
        assertEquals(1,count("agent_plan"));assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM agent_artifact_version WHERE type='STORYBOARD'",Integer.class));
        verify(submit,never()).submitApproved(any(),any());
    }
    // 【测什么】入队失败回滚绑定、新call和workspace；两请求同版本只允许一个确认。
    // 【怎么算红】移除事务或版本守卫，会残留绑定或创建多个新call。
    @Test void enqueueRollbackAndVersionFence() {
        storyboardFailure();var c=command("retry-key");
        doThrow(new IllegalStateException("queue unavailable")).when(jobs).enqueue(eq(AgentRuntime.SKILL_JOB),anyString(),anyString());
        assertThrows(IllegalStateException.class,()->repairs.confirm(1,id,c));assertEquals(0,count("agent_local_repair"));assertEquals(1,count("agent_skill_call"));
        doAnswer(a->{db.update("INSERT INTO job_probe(type,biz,payload) VALUES(?,?,?)",a.getArgument(0),a.getArgument(1),a.getArgument(2));return null;}).when(jobs).enqueue(anyString(),anyString(),anyString());
        repairs.confirm(1,id,c);
        var concurrent=new AgentRepairApplication.Command("other-key",c.expectedWorkspaceVersion(),c.failureId(),c.optionId());
        assertEquals(409,assertThrows(BusinessException.class,()->repairs.confirm(1,id,concurrent)).getCode());
        assertEquals(1,count("agent_local_repair"));
    }
    // 【测什么】参考失效来自实际解析；仅选本人会话准确IMAGE，确认前再次失效409，修复VIDEO不重置IMAGE。
    // 【怎么算红】跳过reference复验或沿用旧绑定，会错误确认或再次在原参考处失败。
    @Test void referenceFailureUsesOwnedValidImageAndRevalidatesBeforeConfirm() {
        videoFailure(true);var advice=repairs.suggestions(1,id);assertEquals("VIDEO_REFERENCE_UNSUPPORTED",advice.path("code").asText());
        var ref=advice.path("options").get(0).path("spec").path("referenceImage");assertTrue(ref.path("mediaPath").asText().startsWith("/api/agent/media/"));assertFalse(ref.has("objectKey"));
        var c=command("reference-repair");
        var image=store.artifactVersion(store.owned(id,1,false),ref.path("artifactId").asText(),ref.path("version").asInt());assertEquals("IMAGE",image.type());
        doThrow(BusinessException.badRequest("图片已过期")).when(refs).resolve(anyLong(),anyString(),any());
        assertEquals(409,assertThrows(BusinessException.class,()->repairs.confirm(1,id,c)).getCode());assertEquals(0,count("agent_local_repair"));
        doReturn(json.createObjectNode().put("title",image.title()).put("mediaPath","/api/agent/media/image-task").put("objectKey","stable-key"))
                .when(refs).resolve(anyLong(),anyString(),argThat(r->r.path("artifactId").asText().equals(image.id())));
        repairs.confirm(1,id,c);run();assertEquals("WAITING_APPROVAL",app.snapshot(1,id).turn().status());
        assertEquals("SUCCEEDED",db.queryForObject("SELECT status FROM agent_plan_step WHERE kind='IMAGE'",String.class));
        assertEquals(1,count("agent_approval"));
    }
    // 【测什么】未知受理、已付费失败及仅同名普通异常均不产生修复权限。
    // 【怎么算红】删除审批/可信来源检查，GET会重新给出候选。
    @Test void paidOrUntrustedFailureCannotBeRepaired() {
        storyboardFailure();var advice=repairs.suggestions(1,id);String call=advice.path("callId").asText();
        var s=store.owned(id,1,false);var turn=store.turn(s.activeTurnId());
        db.update("INSERT INTO agent_approval(id,session_id,turn_id,call_id,epoch,step_no,status,quote_json,request_id,task_id,expires_at) VALUES('paid',?,?,?,?,?,'SUBMITTING','{}','paid-request','paid-task',TIMESTAMPADD(HOUR,1,NOW()))",s.id(),turn.id(),call,turn.epoch(),turn.step());
        assertTrue(repairs.suggestions(1,id).path("options").isEmpty());
        db.update("UPDATE agent_approval SET status='FAILED' WHERE id='paid'");assertTrue(repairs.suggestions(1,id).path("options").isEmpty());
        db.update("DELETE FROM agent_approval WHERE id='paid'");
        var ctx=(com.fasterxml.jackson.databind.node.ObjectNode)store.callContext(call);ctx.remove("localRepairFailure");
        db.update("UPDATE agent_skill_call SET context_json=? WHERE id=?",ctx.toString(),call);
        assertEquals("VIDEO_MODEL_UNAVAILABLE",store.actionableError(s,turn).path("code").asText());
        assertTrue(repairs.suggestions(1,id).path("options").isEmpty());assertEquals(0,count("agent_local_repair"));
    }
    // 【测什么】两个并发确认在会话锁/CAS下恰好一个成功，响应丢失同key仍可重放。
    // 【怎么算红】去掉会话锁/版本守卫，会出现两个新执行或非409冲突。
    @Test void concurrentConfirmHasOneWinner() throws Exception {
        storyboardFailure();var c=command("one");var other=new AgentRepairApplication.Command("two",c.expectedWorkspaceVersion(),c.failureId(),c.optionId());
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);var gate=new java.util.concurrent.CountDownLatch(1);
        try {
            java.util.concurrent.Callable<Integer> one=()->{gate.await();try{repairs.confirm(1,id,c);return 200;}catch(BusinessException e){return e.getCode();}};
            java.util.concurrent.Callable<Integer> two=()->{gate.await();try{repairs.confirm(1,id,other);return 200;}catch(BusinessException e){return e.getCode();}};
            var a=pool.submit(one);var b=pool.submit(two);gate.countDown();
            assertEquals(Set.of(200,409),Set.of(a.get(10,java.util.concurrent.TimeUnit.SECONDS),b.get(10,java.util.concurrent.TimeUnit.SECONDS)));
            assertEquals(1,count("agent_local_repair"));assertEquals(2,count("agent_skill_call"));
        } finally {pool.shutdownNow();}
    }
    // 【测什么】普通继续不采用新规格，旧请求后修改工作区不能确认旧建议。
    // 【怎么算红】把候选生成当作已确认绑定或忽略workspaceVersion，将恢复或接受旧建议。
    @Test void plainResumeDoesNotAdoptAndWorkspaceChangeRejectsOldAdvice() {
        storyboardFailure();var c=command("old");
        app.send(1,id,send("ordinary-continue","继续"));run();run();
        assertEquals("SUSPENDED",app.snapshot(1,id).turn().status());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM agent_artifact_version WHERE type='STORYBOARD'",Integer.class));
        var s=store.owned(id,1,false);var w=store.workspace(s);w.put("version",w.path("version").asLong()+1);store.saveWorkspace(s,w);
        assertEquals(409,assertThrows(BusinessException.class,()->repairs.confirm(1,id,c)).getCode());
        assertEquals(0,count("agent_local_repair"));
        assertTrue(store.repairs().bindings(store,s,w).isEmpty());
        var forged=json.createObjectNode().put("confirmed",true);forged.putObject("data").putArray("_confirmedRepairs").addObject().put("model","open");
        var context=new AgentContext(1L,s.id(),s.activeTurnId(),"self-hosted","goal",null,List.of(),List.of(),0,List.of(),forged,null);
        assertNull(context.confirmedRepair("VIDEO",null));
    }
    // 【测什么】真实HTTP入口调用真实Application/Store，确认响应来自已落库的新执行Snapshot。
    // 【怎么算红】Controller不执行confirm或先取Snapshot，workspace版本及绑定计数断言失败。
    @Test void httpConfirmReturnsPersistedSnapshot() throws Exception {
        storyboardFailure();var c=command("http-confirm");
        var rate=mock(TokenBucketRateLimitService.class);when(rate.tryAcquireDistributed(anyString(),any())).thenReturn(new RateLimitResult(true,0));
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                new org.example.seedancegenarate.agent.api.AgentRepairController(repairs,app,rate)).build();
        var user=new org.example.seedancegenarate.entity.AppUser();user.setId(1L);org.example.seedancegenarate.context.UserContext.setUser(user);
        try {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/agent/conversations/"+id+"/repairs"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.failureId").value(c.failureId()));
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/agent/conversations/"+id+"/repairs").contentType("application/json").content(json.writeValueAsString(c)))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.state.workspace.version").value(c.expectedWorkspaceVersion()+1))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.turn.status").value("WAITING_SKILL"));
            assertEquals(1,count("agent_local_repair"));assertEquals(2,count("agent_skill_call"));assertEquals(0,count("agent_approval"));
        } finally {org.example.seedancegenarate.context.UserContext.clear();}
    }
    ArtifactStorage realReferenceResolver() throws Exception {
        videoFailure(true);
        var image=store.artifacts(store.owned(id,1,false)).stream().filter(a->"IMAGE".equals(a.type())).findFirst().orElseThrow();
        db.execute("CREATE TABLE video_task(biz_task_id VARCHAR PRIMARY KEY,artifact_key VARCHAR)");
        db.update("INSERT INTO video_task VALUES('ref-task','stable-key')");
        db.update("UPDATE agent_artifact_version SET task_id='ref-task' WHERE artifact_id=?",image.id());
        var task=new org.example.seedancegenarate.entity.VideoTask();task.setBizTaskId("ref-task");task.setUserId(1L);
        task.setOutputType("IMAGE");task.setStatus("SUCCESS");task.setArtifactStorageType("OSS");task.setArtifactKey("stable-key");
        var tasks=mock(VideoTaskService.class);when(tasks.getOne(any(),eq(false))).thenReturn(task);
        var storage=mock(ArtifactStorage.class);when(storage.exists("stable-key")).thenAnswer(a->{
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive(),"storage.exists must not run inside a transaction");return true;
        });
        refs=new AgentVideoReference(db,new StoredImageReferences(tasks,mock(ContentModerationPolicy.class),mock(ArtifactExpiryPolicy.class),storage),json);
        repairs=new AgentRepairApplication(store,tx,jobs,engines,access,gateway,refs,json);
        when(engine.models()).thenReturn(java.util.stream.IntStream.range(0,4).mapToObj(i->new ModelSpec("local","ref-"+i,"参考"+i,true,1,1,List.of("16:9"),5,15,List.of(),OutputType.VIDEO).withImageInputMode(ModelSpec.ImageInputMode.REFERENCE_IMAGE)).toList());
        when(access.isOpen(anyString())).thenReturn(true);return storage;
    }
    // 【测什么】真实Resolver→StoredImageReferences→假storage.exists每请求每参考只检查一次，不随模型数重复。
    // 【怎么算红】把resolve留在模型循环且不缓存，会检查同一图片4次。
    @Test void storageChecksAreBoundedPerRequest() throws Exception {
        var storage=realReferenceResolver();assertEquals(4,repairs.suggestions(1,id).path("options").size());
        verify(storage,times(1)).exists("stable-key");
        repairs.suggestions(1,id);verify(storage,times(2)).exists("stable-key");
        doReturn(false).when(storage).exists("stable-key");assertTrue(repairs.suggestions(1,id).path("options").isEmpty());
        verify(storage,times(3)).exists("stable-key");
        doReturn(true).when(storage).exists("stable-key");assertEquals(4,repairs.suggestions(1,id).path("options").size());
        verify(storage,times(4)).exists("stable-key");
    }
    // 【测什么】确认采用时真实存储验证在事务外，成功后的同key重放不再触碰OSS。
    // 【怎么算红】confirm在tx中调用options，假storage.exists的事务断言直接变红。
    @Test void confirmationNeverChecksStorageInsideTransaction() throws Exception {
        var storage=realReferenceResolver();var c=command("outside-tx");clearInvocations(storage);
        repairs.confirm(1,id,c);verify(storage,times(1)).exists("stable-key");assertEquals(1,count("agent_local_repair"));
        doThrow(new IllegalStateException("storage offline after acceptance")).when(storage).exists(anyString());
        repairs.confirm(1,id,c);verify(storage,times(1)).exists("stable-key");assertEquals(1,count("agent_local_repair"));
    }
    // 【测什么】事务外检查之后、短事务之前发生归属/key/版本变化，旧候选409且没有绑定或新call。
    // 【怎么算红】删除事务内纯DB引用复验或failure/version fence，至少一个变化会错误确认。
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"owner","key","workspace"})
    void changesDuringStorageCheckCannotCommit(String change) throws Exception {
        var storage=realReferenceResolver();var c=command("changed-during-check");clearInvocations(storage);
        doAnswer(a->{
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            if("owner".equals(change))db.update("UPDATE agent_artifact_version SET user_id=2 WHERE task_id='ref-task'");
            else if("key".equals(change))db.update("UPDATE video_task SET artifact_key='changed-key' WHERE biz_task_id='ref-task'");
            else {var s=store.owned(id,1,false);var w=store.workspace(s);w.put("version",w.path("version").asLong()+1);store.saveWorkspace(s,w);}
            return true;
        }).when(storage).exists("stable-key");
        assertEquals(409,assertThrows(BusinessException.class,()->repairs.confirm(1,id,c)).getCode());
        assertEquals(0,count("agent_local_repair"));assertEquals(1,count("agent_skill_call"));verify(storage,times(1)).exists("stable-key");
    }
}
