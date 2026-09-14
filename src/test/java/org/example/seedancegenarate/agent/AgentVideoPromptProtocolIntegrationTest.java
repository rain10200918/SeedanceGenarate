package org.example.seedancegenarate.agent;

import org.example.seedancegenarate.agent.runtime.AgentBatchRuntime;
import org.example.seedancegenarate.agent.generation.*;
import org.example.seedancegenarate.agent.model.AgentContext;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AgentVideoPromptProtocolIntegrationTest {
    // 【测什么】第二轮修改前记录真实v3模板和输入的确定性hash，随后冻结为兼容性金标。
    // 【怎么算红】后续更改旧policy、Scene序列化或旧均分预算会使金标不同。
    @Test void legacyBindingGolden() {
        var f=new AgentVideoPromptPreparationTest();var context=f.context(5);var quote=f.first(context);
        var plan=f.preparation.preparePlan(context,quote,AgentBatchRuntime.prepare(f.gateway,context,quote));
        assertEquals("252276d3a16f0bef47e307e4d574a5f0586ab36a6141fc4ce6170f6ee34ad64f",plan.bindingHash());
    }

    String response(AgentVideoPromptPreparationTest f,String key,String body) {
        var root=f.json.createObjectNode();var item=root.putArray("items").addObject().put("key",key);
        item.putObject("sections").put("non_diegetic_music","N/A").put("overall_soundscape","Quiet footsteps.")
                .put("integrated_multimodal_description",body);
        return root.toString();
    }
    // 【测什么】真实Preparation调用字段协议、后端固定次序组装，送到既有Gateway的仍是完整模板正文。
    // 【怎么算红】沿用旧policy/忽略新版parse或只拼模型顺序，将格式、预算或冻结正文断言击红。
    @Test void structuredRoundTripUsesCompactPolicyAndOriginalSubmissionBoundary() throws Exception {
        var f=new AgentVideoPromptPreparationTest();var context=f.context(5);var quote=f.first(context);
        var batch=AgentBatchRuntime.prepare(f.gateway,context,quote);var legacy=f.preparation.preparePlan(context,quote,batch);
        var plan=f.preparation.structuredPlan(legacy);assertNotEquals(legacy.bindingHash(),plan.bindingHash());
        assertTrue(plan.structured());assertEquals(plan.bindingHash(),f.preparation.structuredPlan(
                f.preparation.preparePlan(context.withOutputRepair(true),quote,batch)).bindingHash());
        when(f.models.complete(any(),eq("AGENT_VIDEO_PROMPT"),anyString(),anyString())).thenAnswer(a->{
            String key=f.json.readTree((String)a.getArgument(3)).path("items").get(0).path("key").asText();
            assertTrue(((String)a.getArgument(2)).length()<legacy.scenes().get(0).guide().length());
            return response(f,key,"[Shot 1] A student returns. 我回来了。你好，同学。");
        });
        var prompts=new LinkedHashMap<String,String>();
        for(var scene:plan.scenes())prompts.put(scene.key(),f.preparation.prepareScene(context,plan,scene));
        var result=f.preparation.assemble(plan,prompts);
        assertEquals(2,result.batch().items().size());
        for(var item:result.batch().items()) {
            String prompt=item.quote().inputSnapshot().path("prompt").asText();
            assertEquals(prompts.get(item.sceneId()),prompt);
            assertTrue(prompt.startsWith("integrated_multimodal_description:\n[Shot 1]"));
            assertTrue(prompt.endsWith("non_diegetic_music:\nN/A"));
            assertTrue(prompt.length()<=4000);assertEquals("16:9",item.quote().inputSnapshot().path("ratio").asText());
        }
        verify(f.submit,never()).submitApproved(any(),any());
    }
    // 【测什么】新协议也必须保留原对白与说话人，旧格式不得绕过字段协议。
    // 【怎么算红】去掉版本分流/validateNarration，旧prompt响应或漏台词响应会错误成功。
    @Test void malformedOrMissingSpeechNeverBecomesPreparedPrompt() throws Exception {
        var f=new AgentVideoPromptPreparationTest();var context=f.context(5);var quote=f.first(context);
        var plan=f.preparation.structuredPlan(f.preparation.preparePlan(context,quote,AgentBatchRuntime.prepare(f.gateway,context,quote)));
        var scene=plan.scenes().get(0);
        when(f.models.complete(any(),anyString(),anyString(),anyString())).thenReturn("{\"items\":[{\"key\":\"c1\",\"prompt\":\"old\"}]}");
        assertEquals("STRUCTURED_ITEM_SHAPE",assertThrows(VideoPreparationException.class,()->f.preparation.prepareScene(context,plan,scene)).validationCode());
        when(f.models.complete(any(),anyString(),anyString(),anyString())).thenReturn(response(f,"c1","[Shot 1] A student returns."));
        assertEquals("DIALOGUE_MISSING",assertThrows(VideoPreparationException.class,()->f.preparation.prepareScene(context,plan,scene)).validationCode());
        verify(f.submit,never()).submitApproved(any(),any());
    }

    // 【测什么】12幕真实准备与Gateway组装，复杂幕不再被均分1333限制，总上限与每幕时长保留。
    // 【怎么算红】仍使用旧perPrompt或模型返回跨幕结果，将长度分配、计数或审批前完整组装断言击红。
    @Test void twelveScenesArePreparedIndividuallyWithDeterministicAdaptiveLimits() {
        var f=new AgentVideoPromptPreparationTest();var base=f.context(5);var board=f.json.createObjectNode();var scenes=board.putArray("scenes");
        var items=new ArrayList<AgentBatchRuntime.Item>();var quote=f.first(base);
        for(int i=1;i<=12;i++) {
            scenes.addObject().put("sceneId","s"+i).put("visual",i==7?"复杂镜头细节。".repeat(80):"远景校园")
                    .put("narration","台词"+i).put("duration",15);
            items.add(new AgentBatchRuntime.Item("c"+i,i,f.json.createObjectNode().put("artifactId","board").put("version",1).put("sceneId","s"+i),quote));
        }
        var context=new AgentContext(1L,"session","turn","llm","校园漫剧",null,List.of(),List.of(
                new AgentContext.ArtifactContext("board",1,"STORYBOARD","校园","",board)),0,List.of(),null,base.selection());
        var plan=f.preparation.structuredPlan(f.preparation.preparePlan(context,quote,new AgentBatchRuntime.Prepared("media",items)));
        assertTrue(plan.limit(plan.scenes().get(6))>16000/12);
        assertTrue(plan.scenes().stream().mapToInt(plan::limit).sum()<=16000);
        when(f.models.complete(any(),anyString(),anyString(),anyString())).thenAnswer(a->{
            var input=f.json.readTree((String)a.getArgument(3)).path("items");assertEquals(1,input.size());
            var entry=input.get(0);return response(f,entry.path("key").asText(),"[Shot 1] Campus. "
                    +(entry.path("ordinal").asInt()==7?"Visible movement. ".repeat(90):"")+entry.path("storyboardScene").path("narration").asText());
        });
        var prompts=new LinkedHashMap<String,String>();
        for(var scene:plan.scenes())prompts.put(scene.key(),f.preparation.prepareScene(context,plan,scene));
        var prepared=f.preparation.assemble(plan,prompts);assertEquals(12,prepared.batch().items().size());
        assertTrue(prompts.get("c7").length()>16000/12,"complex scene must actually pass above the old equal-share bound");
        assertEquals(12,prompts.size());verify(f.models,times(12)).complete(any(),eq("AGENT_VIDEO_PROMPT"),anyString(),anyString());
        assertTrue(prepared.batch().items().stream().allMatch(i->i.quote().inputSnapshot().path("duration").asInt()==15));
        int firstLimit=plan.limit(plan.scenes().get(0));assertTrue(firstLimit<4000);
        String exactBody="[Shot 1] Campus. 台词1"+"x".repeat(firstLimit-prompts.get("c1").length());
        doReturn(response(f,"c1",exactBody)).when(f.models).complete(any(),anyString(),anyString(),anyString());
        assertEquals(firstLimit,f.preparation.prepareScene(context,plan,plan.scenes().get(0)).length());
        doReturn(response(f,"c1",exactBody+"x")).when(f.models).complete(any(),anyString(),anyString(),anyString());
        var over=assertThrows(VideoPreparationException.class,()->f.preparation.prepareScene(context,plan,plan.scenes().get(0)));
        assertEquals("PROMPT_TOO_LONG",over.validationCode());assertEquals(1,over.sceneOrdinal());
        prompts.put("c1",prompts.get("c1")+"x".repeat(firstLimit+1-prompts.get("c1").length()));
        assertThrows(VideoPreparationException.class,()->f.preparation.assemble(plan,prompts),"persisted prompt is revalidated against this scene's limit");
    }

    // 【测什么】v4真实字段响应仍逐条验证两人对白归属，只有完整原文和正确编号一起满足才通过。
    // 【怎么算红】新版跳过validateNarration或仅全局contains，交换编号仍含原文也会错误放行。
    @Test void structuredDialogueRequiresExactSpeakerBindings() {
        var f=new AgentVideoPromptPreparationTest();var base=f.context(5);var board=base.artifacts().get(0).data().deepCopy();
        var sceneNode=(com.fasterxml.jackson.databind.node.ObjectNode)board.path("scenes").get(0);
        var dialogue=sceneNode.putObject("sound").putArray("dialogue");
        dialogue.addObject().put("speaker","李白").put("text","此地真美。");
        dialogue.addObject().put("speaker","杜甫").put("text","一起前行吧！");
        var context=new AgentContext(1L,"session","turn","llm",base.goal(),null,List.of(),List.of(
                new AgentContext.ArtifactContext("board",1,"STORYBOARD","校园","",board)),0,List.of(),base.plan(),base.selection());
        var quote=f.first(context);var plan=f.preparation.structuredPlan(f.preparation.preparePlan(context,quote,AgentBatchRuntime.prepare(f.gateway,context,quote)));
        String speech="[Shot 1] 我回来了。The character (S1) says: <d>[Chinese] 此地真美。</d> The character (S2) says: <d>[Chinese] 一起前行吧！</d>";
        when(f.models.complete(any(),anyString(),anyString(),anyString())).thenReturn(response(f,"c1",speech));
        String accepted=f.preparation.prepareScene(context,plan,plan.scenes().get(0));assertTrue(accepted.contains(speech));
        String swapped=speech.replace("(S1)","(TEMP)").replace("(S2)","(S1)").replace("(TEMP)","(S2)");
        when(f.models.complete(any(),anyString(),anyString(),anyString())).thenReturn(response(f,"c1",swapped));
        var failure=assertThrows(VideoPreparationException.class,()->f.preparation.prepareScene(context,plan,plan.scenes().get(0)));
        assertEquals("DIALOGUE_MISSING",failure.validationCode());assertEquals(1,failure.sceneOrdinal());
    }

    // 【测什么】原分镜合法但完整对白加固定格式已装不下时，真实Preparation必须在模型调用前明确拒绝。
    // 【怎么算红】去掉预算保底检查或裁剪源对白，则不报长度错误或原数据发生改变。
    @Test void impossibleSpeechFloorStopsBeforeAnyModelInvocation() throws Exception {
        var f=new AgentVideoPromptPreparationTest();var base=f.context(5);var board=base.artifacts().get(0).data().deepCopy();
        var node=(com.fasterxml.jackson.databind.node.ObjectNode)board.path("scenes").get(0);
        var dialogue=node.putObject("sound").putArray("dialogue");
        for(int i=1;i<=12;i++)dialogue.addObject().put("speaker","角色"+i).put("text","词".repeat(300));
        org.example.seedancegenarate.agent.skill.StoryboardSceneDetails.validate(node);
        var context=new AgentContext(1L,"session","turn","llm",base.goal(),null,List.of(),List.of(
                new AgentContext.ArtifactContext("board",1,"STORYBOARD","校园","",board)),0,List.of(),base.plan(),base.selection());
        String original=context.artifacts().get(0).data().toString();var quote=f.first(context);
        var legacy=f.preparation.preparePlan(context,quote,AgentBatchRuntime.prepare(f.gateway,context,quote));
        var failure=assertThrows(VideoPreparationException.class,()->f.preparation.structuredPlan(legacy));
        assertEquals("VIDEO_PROMPT_TOO_LONG",failure.code());assertEquals(1,failure.sceneOrdinal());
        assertEquals(original,context.artifacts().get(0).data().toString());verifyNoInteractions(f.models);
        verify(f.submit,never()).submitApproved(any(),any());
    }

    // 【测什么】对白/旁白/字幕中的媒体tag只是原文字面量，不能被误判成新增参考而无限格式修复。
    // 【怎么算红】扫描自由正文的所有tag，则无参考输入的合法原文被STRUCTURED_SECTIONS拒绝。
    @Test void sourceSpeechAndVisibleTextMayContainLiteralMediaTags() {
        var f=new AgentVideoPromptPreparationTest();var base=f.context(5);var board=base.artifacts().get(0).data().deepCopy();
        var node=(com.fasterxml.jackson.databind.node.ObjectNode)board.path("scenes").get(0);
        node.put("narration","屏幕标记为<Audio 1>。").put("visual","校园门口，招牌文字为\"<Video 1>\"。");
        node.putObject("sound").putArray("dialogue").addObject().put("speaker","讲解员").put("text","请读<Picture 1>这个标记。");
        var context=new AgentContext(1L,"session","turn","llm",base.goal(),null,List.of(),List.of(
                new AgentContext.ArtifactContext("board",1,"STORYBOARD","校园","",board)),0,List.of(),base.plan(),base.selection());
        var quote=f.first(context);var plan=f.preparation.structuredPlan(f.preparation.preparePlan(context,quote,AgentBatchRuntime.prepare(f.gateway,context,quote)));
        String body="[Shot 1] A sign reads \"<Video 1>\". The character (S1) says: <d>[Chinese] 请读<Picture 1>这个标记。</d> "
                +"The narrator says in an off-screen voiceover: <d>[Chinese] 屏幕标记为<Audio 1>。</d> while lips remain completely closed.";
        when(f.models.complete(any(),anyString(),anyString(),anyString())).thenReturn(response(f,"c1",body));
        assertTrue(f.preparation.prepareScene(context,plan,plan.scenes().get(0)).contains(body));
        assertFalse(plan.scenes().get(0).request().path("parameters").has("referenceImage"));
    }
}
