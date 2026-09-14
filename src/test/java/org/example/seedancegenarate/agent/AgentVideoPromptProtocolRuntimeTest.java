package org.example.seedancegenarate.agent;

import org.example.seedancegenarate.agent.generation.*;
import org.example.seedancegenarate.agent.model.AgentContext;
import org.example.seedancegenarate.agent.skill.TaskQuote;
import org.example.seedancegenarate.service.PromptTemplateService;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Real runtime/SQL/Preparation/compiler/Gateway; only planner, text provider and approval side effects are fakes. */
class AgentVideoPromptProtocolRuntimeTest {
    AgentVideoPreparationRuntimeTest f;
    AgentVideoPromptPreparation actual;
    @BeforeEach void setup() throws Exception {
        f=new AgentVideoPreparationRuntimeTest();f.setup();f.videoSetup();
        var gatewayFixture=new AgentVideoPromptPreparationTest();
        actual=spy(new AgentVideoPromptPreparation(gatewayFixture.gateway,f.models,new PromptTemplateService(),f.json));
        ReflectionTestUtils.setField(f.runtime,"videoPrompts",actual);
        f.quote=new TaskQuote("comfyui","minimax-h3-t2v-hd","H3","VIDEO",f.json.createObjectNode()
                .put("prompt","校园短片").put("duration",15).put("ratio","16:9"),BigDecimal.ONE,"CNY");
        when(f.video.quote(any(),any())).thenReturn(f.quote);
    }
    String fields() {
        var root=f.json.createObjectNode();root.putArray("items").addObject().put("key","single").putObject("sections")
                .put("non_diegetic_music","N/A").put("integrated_multimodal_description","[Shot 1] A student walks into a sunlit campus.")
                .put("overall_soundscape","Footsteps and birds.");
        return root.toString();
    }
    String legacy() {
        var root=f.json.createObjectNode();root.putArray("items").addObject().put("key","single")
                .put("prompt","integrated_multimodal_description:\n[Shot 1] A student walks into a sunlit campus.\n\noverall_soundscape:\nFootsteps and birds.\n\nnon_diegetic_music:\nN/A");
        return root.toString();
    }
    // 【测什么】真实新版模型结构失败后只修复当前幕一次，修复指令仍为sections，最终审批拿到后端组装的冻结文本。
    // 【怎么算红】回退旧协议/错hint/绕过组装或重复Job，会导致错误修复次数、错误冻结文本或多次审批。
    @Test void newCallRepairsStructuredFieldsOnceAndFreezesRenderedPrompt() {
        when(f.models.complete(any(),eq("AGENT_VIDEO_PROMPT"),anyString(),anyString())).thenReturn(legacy(),fields());
        f.startVideo();f.run();assertEquals("WAITING_RETRY",f.app.snapshot(1,f.id).turn().status());
        assertEquals("STRUCTURED_ITEM_SHAPE",f.db.queryForObject("SELECT validation_code FROM agent_video_prompt_checkpoint",String.class));
        f.retryDue();var job=f.next();f.run();f.runtime.execute(job,true);
        assertEquals("WAITING_APPROVAL",f.app.snapshot(1,f.id).turn().status());
        var quote=org.mockito.ArgumentCaptor.forClass(TaskQuote.class);
        verify(f.generation,times(1)).awaitApproval(any(),any(),any(),quote.capture());
        String rendered=quote.getValue().inputSnapshot().path("prompt").asText();
        assertEquals(f.db.queryForObject("SELECT prompt FROM agent_video_prompt_checkpoint",String.class),rendered);
        assertTrue(rendered.startsWith("integrated_multimodal_description:\n"));assertTrue(rendered.endsWith("non_diegetic_music:\nN/A"));
        var policies=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(f.models,times(2)).complete(any(),eq("AGENT_VIDEO_PROMPT"),policies.capture(),anyString());
        assertTrue(policies.getAllValues().get(1).contains(VideoPreparationException.ValidationRule.STRUCTURED_ITEM_SHAPE.repairHint()));
        assertEquals(1,f.db.queryForObject("SELECT repair_count FROM agent_video_prompt_checkpoint",Integer.class));
        System.out.println("STRUCTURED_APPROVAL_FIXTURE\n"+rendered);
    }
    // 【测什么】升级前创建的v3调用在升级后仍用原格式、hash、原修复预算继续，不能重写已经绑定的协议。
    // 【怎么算红】当前call协议检测丢失，升级后会发送sections并拒绝合法旧结果或更换hash。
    @Test void oldCallKeepsLegacyProtocolAfterUpgradeAndReplaysSafely() {
        doAnswer(a->a.getArgument(0)).when(actual).structuredPlan(any());
        when(f.models.complete(any(),anyString(),anyString(),anyString())).thenReturn("invalid",legacy());
        f.startVideo();f.run();assertEquals("WAITING_RETRY",f.app.snapshot(1,f.id).turn().status());
        String binding=f.db.queryForObject("SELECT binding_hash FROM agent_video_prompt_checkpoint",String.class);
        doCallRealMethod().when(actual).structuredPlan(any());
        f.retryDue();f.run();assertEquals("WAITING_APPROVAL",f.app.snapshot(1,f.id).turn().status());
        assertEquals(binding,f.db.queryForObject("SELECT binding_hash FROM agent_video_prompt_checkpoint",String.class));
        verify(actual,times(1)).structuredPlan(any());
        var policies=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(f.models,times(2)).complete(any(),anyString(),policies.capture(),anyString());
        assertTrue(policies.getAllValues().stream().allMatch(p->p.contains("key")&&p.contains("prompt")));
        verify(f.generation,times(1)).awaitApproval(any(),any(),any(),any());
    }
    // 【测什么】模型I/O必须在事务外；取消后新版返回再有效也不能保存或进入费用确认。
    // 【怎么算红】把文本调用移入tx或漏迟到守卫，事务/取消/保存次数断言变红。
    @Test void cancellationDuringStructuredModelCallCannotSaveOrApprove() {
        when(f.models.complete(any(),anyString(),anyString(),anyString())).thenAnswer(a->{
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            f.app.cancel(1,f.id,f.app.snapshot(1,f.id).turn().id());return fields();
        });
        f.startVideo();f.run();assertEquals("CANCELLED",f.app.snapshot(1,f.id).turn().status());
        assertNull(f.db.queryForObject("SELECT prompt FROM agent_video_prompt_checkpoint",String.class));
        verify(f.generation,never()).awaitApproval(any(),any(),any(),any());
    }
}
