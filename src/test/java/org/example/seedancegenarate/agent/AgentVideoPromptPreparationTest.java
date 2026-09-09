package org.example.seedancegenarate.agent;

import com.fasterxml.jackson.databind.*;
import org.example.seedancegenarate.agent.generation.*;
import org.example.seedancegenarate.agent.model.*;
import org.example.seedancegenarate.agent.runtime.AgentBatchRuntime;
import org.example.seedancegenarate.agent.skill.TaskQuote;
import org.example.seedancegenarate.engine.*;
import org.example.seedancegenarate.engine.comfyui.Impl.MiniMaxH3T2vHdWorkflowBuilder;
import org.example.seedancegenarate.entity.VideoTask;
import org.example.seedancegenarate.service.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AgentVideoPromptPreparationTest {
    final ObjectMapper json=new ObjectMapper();
    final String model="minimax-h3-t2v-hd";
    final AgentModelGateway models=mock(AgentModelGateway.class);
    final VideoSubmitService submit=mock(VideoSubmitService.class);
    final AgentGenerationGateway gateway;
    final AgentVideoPromptPreparation preparation;
    AgentVideoPromptPreparationTest() {
        var engine=mock(VideoEngine.class);when(engine.provider()).thenReturn("comfyui");
        when(engine.models()).thenReturn(List.of(new MiniMaxH3T2vHdWorkflowBuilder(json).spec()));
        var access=mock(ModelAccessService.class);when(access.isOpen(model)).thenReturn(true);
        when(submit.estimate(eq("comfyui"),eq(model),anyInt())).thenAnswer(a->new VideoSubmitService.PriceEstimate("comfyui",model,a.getArgument(2),"VIDEO",BigDecimal.ONE,BigDecimal.ONE,"CNY"));
        gateway=new AgentGenerationGateway(new VideoEngineRegistry(List.of(engine)),access,submit,mock(VideoTaskService.class),
                new ContentModerationPolicy(),new ArtifactExpiryPolicy(30),json,mock(AgentDirectGenerationGateway.class));
        preparation=new AgentVideoPromptPreparation(gateway,models,new PromptTemplateService(),json);
    }
    AgentContext context(int secondDuration) {
        var board=json.createObjectNode();var scenes=board.putArray("scenes");
        scenes.addObject().put("sceneId","s1").put("title","清晨").put("visual","冷灰校园，主角走进校门").put("narration","我回来了").put("duration",15);
        scenes.addObject().put("sceneId","s2").put("title","教室").put("visual","主角坐在教室窗边").put("narration","你好，同学").put("duration",secondDuration);
        var plan=json.createObjectNode().put("currentStepId","media");var step=plan.putArray("steps").addObject().put("id","media").put("executionStepId","step-db").put("kind","VIDEO").put("scope","STORYBOARD_SCENES");
        var projected=step.putArray("scenes");
        for(int i=1;i<=2;i++)projected.addObject().put("id","c"+i).put("ordinal",i).put("status","PENDING")
                .set("sourceRef",json.createObjectNode().put("artifactId","board").put("version",1).put("sceneId","s"+i));
        return new AgentContext(1L,"session","turn","llm","校园短片",null,List.of(),
                List.of(new AgentContext.ArtifactContext("board",1,"STORYBOARD","校园","完整分镜",board)),0,List.of(),plan,new AgentContext.ArtifactRef("board",1,"s1"));
    }
    TaskQuote first(AgentContext context) {
        return gateway.quoteVideo(context,json.createObjectNode().put("model",model).put("prompt","导演首幕初稿")
                .put("duration",15).put("ratio","16:9").put("visualStyle","冷灰校园短漫剧"));
    }
    String prompt(String action) {
        return "integrated_multimodal_description:\n[Shot 1] Cold gray campus, "+action+". Dialogue: 我回来了。你好，同学。\n\noverall_soundscape:\nQuiet footsteps.\n\nnon_diegetic_music:\nN/A";
    }
    String output(String first,String second) {
        var root=json.createObjectNode();var items=root.putArray("items");
        items.addObject().put("key","c1").put("prompt",first);items.addObject().put("key","c2").put("prompt",second);return root.toString();
    }
    // 【测什么】逐条保留结构化台词归属，允许模板分段旁白；缺句或对调说话人仍拒绝。
    // 【怎么算红】恢复整段contains或只全局找台词，合法分段失败或对调角色被放行。
    @Test void structuredSpeechPassesThroughAndValidatesIndividualAttribution() throws Exception {
        var original=context(5);var board=original.artifacts().get(0).data().deepCopy();
        var s=(com.fasterxml.jackson.databind.node.ObjectNode)board.path("scenes").get(0);
        s.put("narration","太阳升起\n微风吹来");
        s.putObject("shot").put("cameraMovement","缓慢推近");
        s.putArray("characters").addObject().put("name","小猫").put("wardrobe","蓝围巾");
        var sound=s.putObject("sound").put("narration","太阳升起\n微风吹来");
        sound.putArray("dialogue").addObject().put("speaker","小猫").put("text","你好呀");
        ((com.fasterxml.jackson.databind.node.ArrayNode)sound.get("dialogue")).addObject().put("speaker","朋友").put("text","早上好");
        var context=new AgentContext(1L,"session","turn","llm","校园短片",null,List.of(),
                List.of(new AgentContext.ArtifactContext("board",1,"STORYBOARD","校园","分镜",board)),0,List.of(),original.plan(),original.selection());
        var first=first(context);var plan=preparation.preparePlan(context,first,AgentBatchRuntime.prepare(gateway,context,first));
        var scene=plan.scenes().get(0);
        assertEquals("蓝围巾",scene.request().path("storyboardScene").path("characters").get(0).path("wardrobe").asText());
        String valid=prompt("太阳升起。镜头切换，微风吹来。小猫：你好呀。朋友: 早上好");
        var response=json.createObjectNode();response.putArray("items").addObject().put("key",scene.key()).put("prompt",valid);
        when(models.complete(any(),anyString(),anyString(),anyString())).thenReturn(response.toString());
        assertEquals(valid,preparation.prepareScene(context,plan,scene));
        for(String invalid:List.of(valid.replace("小猫：你好呀。朋友: 早上好","朋友：你好呀。小猫:早上好"),valid.replace("微风吹来",""))) {
            ((com.fasterxml.jackson.databind.node.ObjectNode)response.path("items").get(0)).put("prompt",invalid);
            when(models.complete(any(),anyString(),anyString(),anyString())).thenReturn(response.toString());
            assertThrows(VideoPreparationException.class,()->preparation.prepareScene(context,plan,scene));
        }
        verify(submit,never()).submitApproved(any(),any());
    }
    // 【测什么】所有幕实际注入T2vHD模板和各自旁白/时长，最终英文提示词入冻结报价且提交不再优化。
    // 【怎么算红】省掉模型准备、只优化首幕或重报价再次拼前缀，逐幕正文/参数/提交字节断言失败。
    @Test void everySceneUsesModelTemplateAndFrozenBytesSurviveSubmit() throws Exception {
        var context=context(5);var first=first(context);var batch=AgentBatchRuntime.prepare(gateway,context,first);
        when(models.complete(any(),eq("AGENT_VIDEO_PROMPT"),anyString(),anyString())).thenAnswer(a->{
            String policy=a.getArgument(2);assertTrue(policy.contains("MiniMax-H3 文生视频高清版"));assertTrue(policy.contains("integrated_multimodal_description:"));
            var request=json.readTree((String)a.getArgument(3)).path("items");
            assertEquals(15,request.get(0).path("parameters").path("duration").asInt());assertEquals(5,request.get(1).path("parameters").path("duration").asInt());
            assertEquals("我回来了",request.get(0).path("storyboardScene").path("narration").asText());assertEquals("你好，同学",request.get(1).path("storyboardScene").path("narration").asText());
            return output(prompt("the student enters the gate"),prompt("the student sits beside the window"));
        });
        var result=preparation.prepare(context,first,batch);assertEquals(2,result.batch().items().size());
        var task=new VideoTask();task.setBizTaskId("accepted");when(submit.submitApproved(any(),any())).thenReturn(task);
        for(int i=0;i<2;i++) {
            var quote=result.batch().items().get(i).quote();String compiled=prompt(i==0?"the student enters the gate":"the student sits beside the window");
            assertEquals(compiled,quote.inputSnapshot().path("prompt").asText());
            assertEquals("冷灰校园短漫剧",quote.inputSnapshot().path("visualStyle").asText());
            assertEquals(batch.items().get(i).quote().amount(),quote.amount());
            assertEquals("accepted",gateway.submit(1,quote,"request-"+i));
            verify(submit).submitApproved(argThat(r->r.prompt().equals(compiled)&&r.duration()==(compiled.contains("gate")?15:5)),any());
        }
        verify(models,times(1)).complete(any(),eq("AGENT_VIDEO_PROMPT"),anyString(),anyString());
    }
    // 【测什么】首幕15秒合法但第二幕16秒不支持时，明确指出第2幕且准备模型/任务零调用。
    // 【怎么算红】复制首幕时长或直接clamp，异常/ordinal断言失败，模型可能被错误调用。
    @Test void laterUnsupportedDurationFailsBeforeAnyPromptInvocation() throws Exception {
        var context=context(16);var first=first(context);
        var failure=assertThrows(VideoPreparationException.class,()->{
            var batch=AgentBatchRuntime.prepare(gateway,context,first);preparation.prepare(context,first,batch);
        });
        assertEquals("VIDEO_DURATION_UNSUPPORTED",failure.code());assertEquals(2,failure.sceneOrdinal());
        assertTrue(failure.getMessage().contains("16 秒"));assertTrue(failure.getMessage().contains("5～15"));
        verifyNoInteractions(models);verify(submit,never()).submitApproved(any(),any());
    }
    // 【测什么】缺幕/重幕/未知key/不完整JSON/缺模板段落均不能生成半份可批准报价。
    // 【怎么算红】取消准确集合、JSON严格解析或模板段落检查，至少一个无效输出被接受。
    @Test void malformedOrIncompletePreparationNeverReturnsPartialQuotes() throws Exception {
        var context=context(5);var first=first(context);var batch=AgentBatchRuntime.prepare(gateway,context,first);
        for(String invalid:List.of("{\"items\":[]}",output(prompt("one"),prompt("two")).replace("c2","c1"),
                output(prompt("one"),prompt("two")).replace("c2","foreign"),"{\"items\":[",output("只是视觉原文","只是视觉原文"),output(prompt("one"),"x".repeat(4001)),
                output(prompt("one"),prompt("two").replace("你好，同学","N/A")))) {
            when(models.complete(any(),anyString(),anyString(),anyString())).thenReturn(invalid);
            var failure=assertThrows(VideoPreparationException.class,()->preparation.prepare(context,first,batch));
            assertEquals("VIDEO_PROMPT_OUTPUT_INVALID",failure.code());assertFalse(failure.getMessage().contains(invalid));
        }
        verify(submit,never()).submitApproved(any(),any());
    }
    // 【测什么】一个准备作业只请求一个精确幕，已完成幕可独立持有，全部完成前不能组装审批。
    // 【怎么算红】prepareScene发送整批或assemble接受缺幕，单项请求/缺幕异常断言失败。
    @Test void oneSceneInvocationAndCompleteOnlyAssembly() throws Exception {
        var context=context(5);var first=first(context);var batch=AgentBatchRuntime.prepare(gateway,context,first);
        var plan=preparation.preparePlan(context,first,batch);verifyNoInteractions(models);
        when(models.complete(any(),eq("AGENT_VIDEO_PROMPT"),anyString(),anyString())).thenAnswer(a->{
            var items=json.readTree((String)a.getArgument(3)).path("items");assertEquals(1,items.size());
            String key=items.get(0).path("key").asText();
            assertEquals(key.equals("c1")?15:5,items.get(0).path("parameters").path("duration").asInt());
            var result=json.createObjectNode();result.putArray("items").addObject().put("key",key).put("prompt",prompt(key));return result.toString();
        });
        String one=preparation.prepareScene(context,plan,plan.scenes().get(0));
        assertThrows(VideoPreparationException.class,()->preparation.assemble(plan,Map.of("c1",one)));
        String two=preparation.prepareScene(context,plan,plan.scenes().get(1));
        var result=preparation.assemble(plan,Map.of("c1",one,"c2",two));
        assertEquals(one,result.batch().items().get(0).quote().inputSnapshot().path("prompt").asText());
        assertEquals(two,result.batch().items().get(1).quote().inputSnapshot().path("prompt").asText());
        verify(models,times(2)).complete(any(),eq("AGENT_VIDEO_PROMPT"),anyString(),anyString());
        verify(submit,never()).submitApproved(any(),any());
    }
    // 【测什么】单幕返回另一幕/重键/缺旁白仍拒绝，安全错误携带实际失败幕序号。
    // 【怎么算红】移除单幕key集合或旁白检查，至少一条坏正文能通过；漏atScene则序号不是2。
    @Test void invalidSingleSceneReportsItsOwnOrdinal() {
        var context=context(5);var first=first(context);var plan=preparation.preparePlan(context,first,AgentBatchRuntime.prepare(gateway,context,first));
        var invalid=json.createObjectNode();invalid.putArray("items").addObject().put("key","c2").put("prompt",prompt("second").replace("你好，同学","N/A"));
        for(String raw:List.of(output(prompt("one"),prompt("two")),invalid.toString(),"{\"items\":[],\"items\":[]}")) {
            when(models.complete(any(),anyString(),anyString(),anyString())).thenReturn(raw);
            var failure=assertThrows(VideoPreparationException.class,()->preparation.prepareScene(context,plan,plan.scenes().get(1)));
            assertEquals(2,failure.sceneOrdinal());assertEquals("VIDEO_PROMPT_OUTPUT_INVALID",failure.code());
        }
    }
    // 【测什么】checkpoint完整身份包含模型通道、输入图、模板内容、分镜版本及逐幕真实规格。
    // 【怎么算红】去掉对应hash输入项，改变该条件后bindingHash仍相同，这条变红。
    @Test void bindingChangesForEveryPromptAffectingInput() {
        var context=context(5);var first=first(context);var batch=AgentBatchRuntime.prepare(gateway,context,first);
        String original=preparation.preparePlan(context,first,batch).bindingHash();
        assertEquals(original,preparation.preparePlan(context,first,batch).bindingHash());
        assertNotEquals(original,preparation.preparePlan(context.withModelBinding("changed-llm"),first,batch).bindingHash());
        assertNotEquals(original,preparation.preparePlan(context.withImageAssetIds(List.of("123")),first,batch).bindingHash());
        var changedContext=context(6);var changedFirst=first(changedContext);
        assertNotEquals(original,preparation.preparePlan(changedContext,changedFirst,AgentBatchRuntime.prepare(gateway,changedContext,changedFirst)).bindingHash());
        var old=batch.items().get(0);var source=old.source().deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)source).put("version",2);
        var changedBatch=new AgentBatchRuntime.Prepared(batch.stepId(),List.of(new AgentBatchRuntime.Item(old.sceneId(),old.ordinal(),source,old.quote()),batch.items().get(1)));
        assertNotEquals(original,preparation.preparePlan(context,first,changedBatch).bindingHash());
        var changedTemplates=mock(PromptTemplateService.class);
        when(changedTemplates.resolve(any())).thenAnswer(a->{
            var originalTemplate=new PromptTemplateService().resolve(a.getArgument(0));
            return new PromptTemplateService.ResolvedTemplate(originalTemplate.guide()+"\nTemplate revision two",originalTemplate.resourcePath(),false);
        });
        var changed=new AgentVideoPromptPreparation(gateway,models,changedTemplates,json);
        assertNotEquals(original,changed.preparePlan(context,first,batch).bindingHash());
        verifyNoInteractions(models);
    }
    // 【测什么】单独准备分镜第三幕时，准确版本中的实际序号和sourceRef随安全失败返回。
    // 【怎么算红】单幕ordinal写死1或按旧版本寻找，失败场景变成第1幕，来源/序号断言失败。
    @Test void singleThirdSceneUsesExactVersionOrdinalAndSource() {
        var base=context(5);var board=base.artifacts().get(0).data().deepCopy();
        ((com.fasterxml.jackson.databind.node.ArrayNode)board.path("scenes")).addObject()
                .put("sceneId","s3").put("visual","放学归途").put("narration","第三幕旁白").put("duration",5);
        var old=json.createObjectNode();old.putArray("scenes").addObject().put("sceneId","s3").put("visual","旧版本首幕");
        var context=new AgentContext(1L,"session","turn","llm","校园短片",null,List.of(),
                List.of(new AgentContext.ArtifactContext("board",1,"STORYBOARD","旧分镜","旧版本",old),
                        new AgentContext.ArtifactContext("board",7,"STORYBOARD","新分镜","新版本",board)),0,List.of(),
                null,new AgentContext.ArtifactRef("board",7,"s3"));
        var plan=preparation.preparePlan(context,first(context),null);
        assertEquals(3,plan.scenes().get(0).ordinal());
        when(models.complete(any(),anyString(),anyString(),anyString())).thenReturn("{}");
        var failure=assertThrows(VideoPreparationException.class,()->preparation.prepareScene(context,plan,plan.scenes().get(0)));
        assertEquals(3,failure.sceneOrdinal());assertEquals("s3",failure.sourceRef().path("sceneId").asText());
        assertEquals(7,failure.sourceRef().path("version").asInt());assertEquals("board",failure.sourceRef().path("artifactId").asText());
    }
    // 【测什么】回放用户13:53的真实响应：完整编号对白通过，原漏字/错编号/对调仍拒绝。
    // 【怎么算红】恢复只接受中文邻接名会拒绝真实修正结果；去掉完整台词或编号校验会放行反例。
    @Test void productionDialogueReplayUsesSourceAssignedSpeakerIds() throws Exception {
        var base=context(5);var board=base.artifacts().get(0).data().deepCopy();
        var sceneData=(com.fasterxml.jackson.databind.node.ObjectNode)board.path("scenes").get(0);
        sceneData.remove("narration");
        var lines=sceneData.putObject("sound").putArray("dialogue");
        lines.addObject().put("speaker","老猴").put("text","哪一个有本事的，钻进去寻个源头，不伤身体，我等即拜他为王！");
        lines.addObject().put("speaker","石猴").put("text","我进去！我进去！");
        var context=new AgentContext(1L,"session","turn","llm","西游记",null,List.of(),
                List.of(new AgentContext.ArtifactContext("board",1,"STORYBOARD","分镜","正文",board)),0,List.of(),base.plan(),base.selection());
        var first=first(context);var plan=preparation.preparePlan(context,first,AgentBatchRuntime.prepare(gateway,context,first));
        assertEquals("老猴",plan.scenes().get(0).request().path("speakerBindings").get(0).path("speaker").asText());
        assertEquals("S1",plan.scenes().get(0).request().path("speakerBindings").get(0).path("id").asText());
        String repaired;
        String missing;
        try(var in=getClass().getResourceAsStream("/agent/video-prompt-repaired-dialogue.json")) {repaired=new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);}
        try(var in=getClass().getResourceAsStream("/agent/video-prompt-missing-word.json")) {missing=new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);}
        repaired=repaired.replace("76f8c6b8-158a-4a8f-acbc-9817235fab62","c1");
        missing=missing.replace("76f8c6b8-158a-4a8f-acbc-9817235fab62","c1");
        when(models.complete(any(),anyString(),anyString(),anyString())).thenReturn(repaired);
        assertEquals(json.readTree(repaired).path("items").get(0).path("prompt").asText(),preparation.prepareScene(context,plan,plan.scenes().get(0)));
        for(String invalid:List.of(missing,repaired.replace("我等即拜他为王","我等即拜为王"),
                repaired.replace("(S1)","(S9)"),repaired.replace("(S1)","(SWAP)").replace("(S2)","(S1)").replace("(SWAP)","(S2)"))) {
            when(models.complete(any(),anyString(),anyString(),anyString())).thenReturn(invalid);
            assertEquals("DIALOGUE_MISSING",assertThrows(VideoPreparationException.class,
                    ()->preparation.prepareScene(context,plan,plan.scenes().get(0))).validationCode());
        }
        verify(submit,never()).submitApproved(any(),any());
    }
    AgentContext changedContext(AgentContext base,String goal,List<String> choices) {
        return new AgentContext(base.userId(),base.sessionId(),"resumed-turn",base.channel(),goal,"临时执行摘要",
                List.of(new AgentContext.HistoryMessage("ASSISTANT","准备第二幕")),base.artifacts(),99,choices,
                base.plan(),base.selection(),json.createObjectNode().put("noise","retry"),json.createObjectNode().put("noise","progress"),
                base.outputRepair(),base.imageAssetIds(),base.modelBinding());
    }
    // 【测什么】稳定创作要求进入hash；恢复消息/摘要/进度噪声不进入模型正文，也不使已通过幕失效。
    // 【怎么算红】去掉goal/choices hash或把原context传模型，hash差异或实际模型入参的空噪声断言失败。
    @Test void dedicatedContextBindsCreativeFactsAndExcludesResumeNoise() {
        var base=context(5);var first=first(base);var batch=AgentBatchRuntime.prepare(gateway,base,first);
        String original=preparation.preparePlan(base,first,batch).bindingHash();
        var resumed=changedContext(base,base.goal(),base.confirmedChoices());
        var resumedPlan=preparation.preparePlan(resumed,first,batch);
        assertEquals(original,resumedPlan.bindingHash());
        assertNotEquals(original,preparation.preparePlan(changedContext(base,"改为黑白默片",base.confirmedChoices()),first,batch).bindingHash());
        assertNotEquals(original,preparation.preparePlan(changedContext(base,base.goal(),List.of("保留灰色校服")),first,batch).bindingHash());
        when(models.complete(any(),eq("AGENT_VIDEO_PROMPT"),anyString(),anyString())).thenAnswer(a->{
            AgentContext sent=a.getArgument(0);
            assertEquals(resumed.turnId(),sent.turnId());assertEquals(99,sent.step());assertEquals(base.goal(),sent.goal());
            assertEquals(base.userId(),sent.userId());assertEquals(base.sessionId(),sent.sessionId());assertEquals(base.channel(),sent.channel());
            assertNull(sent.summary());assertNull(sent.plan());assertNull(sent.selection());assertNull(sent.observations());assertNull(sent.recipe());
            assertTrue(sent.messages().isEmpty());assertTrue(sent.artifacts().isEmpty());
            var response=json.createObjectNode();response.putArray("items").addObject().put("key","c1").put("prompt",prompt("first"));
            return response.toString();
        });
        assertEquals(prompt("first"),preparation.prepareScene(resumed,resumedPlan,resumedPlan.scenes().get(0)));
    }
    // 【测什么】完整JSON围栏和模板标题同行正文被接受，正文、时长及参考规格不重写。
    // 【怎么算红】恢复标题独占行或直接解析围栏，会拒绝合法输出；改正文则精确字节断言失败。
    @Test void harmlessWholeResponseFencesAndInlineSectionsPreservePrompt() throws Exception {
        var context=context(5);var first=first(context);var plan=preparation.preparePlan(context,first,AgentBatchRuntime.prepare(gateway,context,first));
        String text=prompt("first").replace(":\n",": ");
        var response=json.createObjectNode();response.putArray("items").addObject().put("key","c1").put("prompt",text);
        for(String fence:List.of("```","`")) {
            when(models.complete(any(),anyString(),anyString(),anyString())).thenReturn(fence+"json\n"+response+"\n"+fence);
            assertEquals(text,preparation.prepareScene(context,plan,plan.scenes().get(0)));
        }
        assertEquals("prompts/minimax-h3-t2v-hd.md",plan.scenes().get(0).templateId());
        verify(submit,never()).submitApproved(any(),any());
    }
    // 【测什么】无害容错不放开说明文字、重复键、错幕、缺段落或空段落，错误保留安全字段位置。
    // 【怎么算红】任意截JSON或移除上述校验，至少一个坏输出被接受；统一包装则细分类断言失败。
    @Test void rejectsUnsafeOrIncompleteOutputWithSpecificRules() throws Exception {
        var context=context(5);var first=first(context);var plan=preparation.preparePlan(context,first,AgentBatchRuntime.prepare(gateway,context,first));
        var response=json.createObjectNode();response.putArray("items").addObject().put("key","c1").put("prompt",prompt("first"));
        var cases=new java.util.LinkedHashMap<String,String>();
        cases.put("说明：\n"+response,"JSON_INVALID");cases.put(response+" {}","JSON_INVALID");
        cases.put("```json\n"+response+"\n`","JSON_INVALID");cases.put("{\"items\":[],\"items\":[]}","JSON_INVALID");
        cases.put(response.toString().replace("c1","foreign"),"SCENE_KEY_MISMATCH");
        cases.put(response.toString().replace("overall_soundscape:","other_section:"),"TEMPLATE_SECTION_MISSING");
        cases.put(response.toString().replace("Quiet footsteps.",""),"TEMPLATE_SECTION_EMPTY");
        cases.put(response.toString().replace("我回来了","PRIVATE_RAW"),"DIALOGUE_MISSING");
        cases.forEach((raw,code)->{
            when(models.complete(any(),anyString(),anyString(),anyString())).thenReturn(raw);
            var failure=assertThrows(VideoPreparationException.class,()->preparation.prepareScene(context,plan,plan.scenes().get(0)));
            assertEquals(code,failure.validationCode());assertTrue(failure.validationDetail().startsWith("$"));
            assertNotNull(failure.diagnosticId());assertEquals(1,failure.sceneOrdinal());
            assertFalse(failure.getMessage().contains("PRIVATE_RAW"));assertFalse(failure.validationDetail().contains("PRIVATE_RAW"));
            assertFalse(failure.repairHint().contains("PRIVATE_RAW"));
            assertEquals(failure.diagnosticId(),failure.atScene(3).withSource(plan.scenes().get(0).source()).diagnosticId());
        });
        verify(submit,never()).submitApproved(any(),any());
    }
    // 【测什么】修正输入只接受平台固定规则，不能通过repairHint注入模型原文或任意指令。
    // 【怎么算红】直接拼接参数、改场景请求或重发整批，提示词/请求断言失败。
    @Test void repairContextContainsOnlyTrustedRuleAndOriginalSingleScene() throws Exception {
        var context=context(5);var first=first(context);var plan=preparation.preparePlan(context,first,AgentBatchRuntime.prepare(gateway,context,first));
        var response=json.createObjectNode();response.putArray("items").addObject().put("key","c1").put("prompt",prompt("first"));
        var requests=new java.util.ArrayList<String>();var policies=new java.util.ArrayList<String>();
        when(models.complete(any(),anyString(),anyString(),anyString())).thenAnswer(a->{policies.add(a.getArgument(2));requests.add(a.getArgument(3));return response.toString();});
        preparation.prepareScene(context,plan,plan.scenes().get(0),"PRIVATE_RAW arbitrary instruction");
        preparation.prepareScene(context,plan,plan.scenes().get(0),VideoPreparationException.ValidationRule.JSON_INVALID.repairHint());
        assertFalse(policies.get(0).contains("PRIVATE_RAW"));assertFalse(policies.get(0).contains("本次仅修正"));
        assertTrue(policies.get(1).contains("本次仅修正"));assertEquals(requests.get(0),requests.get(1));
        verify(submit,never()).submitApproved(any(),any());
    }
    // 【测什么】模型模板的d标签可与显式角色名相邻保留对白，S编号猜身份/对调说话人/缺字仍被拒绝。
    // 【怎么算红】仅contains角色:台词会拒绝合法H3；只检查台词内容或S声明会放过错误归属。
    @Test void h3DialogueRequiresAdjacentLiteralSpeakerAndCompleteText() throws Exception {
        var base=context(5);var board=base.artifacts().get(0).data().deepCopy();
        var sceneData=(com.fasterxml.jackson.databind.node.ObjectNode)board.path("scenes").get(0);
        sceneData.putObject("sound").putArray("dialogue").addObject().put("speaker","美猴王").put("text","我定要寻个神仙！");
        var context=new AgentContext(1L,"session","turn","llm","校园短片",null,List.of(),
                List.of(new AgentContext.ArtifactContext("board",1,"STORYBOARD","分镜","正文",board)),0,List.of(),base.plan(),base.selection());
        var first=first(context);var plan=preparation.preparePlan(context,first,AgentBatchRuntime.prepare(gateway,context,first));
        String dialogue="美猴王 (S1) says: <d>[Chinese] 我定要寻个神仙！</d>";
        var response=json.createObjectNode();var item=response.putArray("items").addObject().put("key","c1");
        when(models.complete(any(),anyString(),anyString(),anyString())).thenAnswer(a->{
            assertTrue(((String)a.getArgument(2)).contains("角色名不要放进d标签"));return response.toString();
        });
        for(String valid:List.of(dialogue,"The character "+dialogue,"Character introduction\n"+dialogue,
                dialogue.replace(" says: <d>[Chinese] ","\n says : <d>\n[Chinese]\n"))) {
            item.put("prompt",prompt("the monkey wakes. "+valid));
            assertEquals(item.path("prompt").asText(),preparation.prepareScene(context,plan,plan.scenes().get(0)));
        }
        for(String invalid:List.of(dialogue.replace("美猴王","老猴"),dialogue.replace("美猴王 ",""),
                dialogue.replace("我定要寻个神仙！","我定要寻仙！"),"美猴王 is S1. "+dialogue.replace("美猴王 ",""),
                dialogue.replace("美猴王 (S1)","坏美猴王 (S1)"))) {
            item.put("prompt",prompt("the monkey wakes. "+invalid));
            var failure=assertThrows(VideoPreparationException.class,()->preparation.prepareScene(context,plan,plan.scenes().get(0)));
            assertEquals("DIALOGUE_MISSING",failure.validationCode());
        }
        verify(submit,never()).submitApproved(any(),any());
    }
    // 【测什么】普通角色:台词保留完整说话人边界；英语说明后空白合法，中文伪前缀和对调归属拒绝。
    // 【怎么算红】恢复normalized.contains判断对白，坏美猴王/假老猴前缀能绕过断言。
    @Test void plainDialogueRequiresWholeSpeakerWithOriginalWhitespaceBoundary() {
        var base=context(5);var board=base.artifacts().get(0).data().deepCopy();
        var sceneData=(com.fasterxml.jackson.databind.node.ObjectNode)board.path("scenes").get(0);
        var lines=sceneData.putObject("sound").putArray("dialogue");
        lines.addObject().put("speaker","美猴王").put("text","我定要寻个神仙！");
        lines.addObject().put("speaker","老猴").put("text","大王慢走。");
        var context=new AgentContext(1L,"session","turn","llm","校园短片",null,List.of(),
                List.of(new AgentContext.ArtifactContext("board",1,"STORYBOARD","分镜","正文",board)),0,List.of(),base.plan(),base.selection());
        var first=first(context);var plan=preparation.preparePlan(context,first,AgentBatchRuntime.prepare(gateway,context,first));
        var response=json.createObjectNode();var item=response.putArray("items").addObject().put("key","c1");
        when(models.complete(any(),anyString(),anyString(),anyString())).thenAnswer(a->response.toString());
        for(String valid:List.of("美猴王:我定要寻个神仙！ 老猴：大王慢走。",
                "The character 美猴王 : 我定要寻个神仙！\nThe elder\n老猴：大王慢走。",
                "美猴王：我定要\n寻个神仙！ 老猴 : 大王慢走。")) {
            item.put("prompt",prompt("the monkey wakes. "+valid));
            assertEquals(item.path("prompt").asText(),preparation.prepareScene(context,plan,plan.scenes().get(0)));
        }
        for(String invalid:List.of("坏美猴王:我定要寻个神仙！ 老猴：大王慢走。",
                "美猴王:我定要寻个神仙！ 假老猴：大王慢走。","老猴:我定要寻个神仙！ 美猴王：大王慢走。")) {
            item.put("prompt",prompt("the monkey wakes. "+invalid));
            var failure=assertThrows(VideoPreparationException.class,()->preparation.prepareScene(context,plan,plan.scenes().get(0)));
            assertEquals("DIALOGUE_MISSING",failure.validationCode());
        }
    }
}
