package org.example.seedancegenarate.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.seedancegenarate.agent.generation.*;
import org.example.seedancegenarate.agent.skill.*;
import org.example.seedancegenarate.agent.model.AgentContext;
import org.example.seedancegenarate.engine.*;
import org.example.seedancegenarate.entity.VideoTask;
import org.example.seedancegenarate.exception.BusinessException;
import org.example.seedancegenarate.service.*;
import org.example.seedancegenarate.service.Impl.WalletServiceImpl;
import org.junit.jupiter.api.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentGenerationGatewayTest {
    final ObjectMapper json=new ObjectMapper(); VideoEngine engine; ModelAccessService access; VideoSubmitService submit;
    VideoTaskService tasks; AgentGenerationGateway gateway; AgentDirectGenerationGateway direct;
    @BeforeEach void setup() {
        engine=mock(VideoEngine.class); when(engine.provider()).thenReturn("local");
        when(engine.models()).thenReturn(List.of(
                new ModelSpec("local","image-1","图片模型",false,0,0,List.of("1:1"),0,0,List.of(),OutputType.IMAGE),
                new ModelSpec("local","video-1","视频模型",false,0,0,List.of("16:9"),4,10,List.of(4,8),OutputType.VIDEO),
                new ModelSpec("local","edit-1","编辑模型",true,1,1,List.of("1:1"),0,0,List.of(),OutputType.IMAGE)));
        access=mock(ModelAccessService.class); when(access.isOpen(anyString())).thenReturn(true);
        submit=mock(VideoSubmitService.class); tasks=mock(VideoTaskService.class);
        direct=mock(AgentDirectGenerationGateway.class);
        gateway=new AgentGenerationGateway(new VideoEngineRegistry(List.of(engine)),access,submit,tasks,new ContentModerationPolicy(),new ArtifactExpiryPolicy(30),json,direct);
        when(submit.estimate("local","image-1",8)).thenReturn(new VideoSubmitService.PriceEstimate("local","image-1",8,"IMAGE",BigDecimal.ONE,BigDecimal.ONE,"CNY"));
    }
    com.fasterxml.jackson.databind.JsonNode input() { return json.createObjectNode().put("model","image-1").put("prompt","气象雷达"); }
    VideoTask task() { var t=new VideoTask(); t.setUserId(1L);t.setBizTaskId("tsk_test");t.setStatus("SUCCESS");t.setOutputType("IMAGE");t.setArtifactStorageType("OSS");t.setArtifactKey("outputs/private.png");t.setCreateTime(LocalDateTime.now());return t; }

    // 【测什么】Skill报价不提交任务，不接受任意URL、未开放模型、参考图模型或不支持的画幅。
    // 【怎么算红】删掉字段白名单/模型过滤/画幅校验时对应assertThrows失败。
    @Test void quoteIsReadOnlyAndValidatesCapabilities() throws Exception {
        var quote=gateway.quote("IMAGE",input()); assertEquals("1:1",quote.inputSnapshot().path("ratio").asText());assertEquals(BigDecimal.ONE,quote.amount());
        verify(submit,never()).submitApproved(any(),any());verifyNoInteractions(tasks);
        assertThrows(BusinessException.class,()->gateway.quote("IMAGE",json.createObjectNode().put("model","image-1").put("prompt","x").put("url","http://127.0.0.1")));
        assertThrows(BusinessException.class,()->gateway.quote("IMAGE",json.createObjectNode().put("model","edit-1").put("prompt","x")));
        assertThrows(BusinessException.class,()->gateway.quote("IMAGE",json.createObjectNode().put("model","image-1").put("prompt","x").put("ratio","21:9")));
        when(access.isOpen("image-1")).thenReturn(false);assertThrows(BusinessException.class,()->gateway.quote("IMAGE",input()));
    }
    // 【测什么】报价变化拒绝执行；已有受理任务优先返回同taskId，即使模型已停用。
    // 【怎么算红】移除金额比较会调用submitApproved；移除accepted快路会因停用抛错。
    @Test void changedPriceRejectsButAcceptedRequestReplays() throws Exception {
        var approved=gateway.quote("IMAGE",input());
        when(submit.estimate("local","image-1",8)).thenReturn(new VideoSubmitService.PriceEstimate("local","image-1",8,"IMAGE",BigDecimal.TEN,BigDecimal.TEN,"CNY"));
        assertThrows(GenerationRejectedException.class,()->gateway.submit(1,approved,"approval-1"));verify(submit,never()).submitApproved(any(),any());
        when(submit.findAcceptedByRequestId(1L,"approval-1")).thenReturn(task());when(access.isOpen(anyString())).thenReturn(false);
        assertEquals("tsk_test",gateway.submit(1,approved,"approval-1"));
    }
    // 【测什么】余额不足只有确认没有任务后才能安全拒绝，半成品/未知提交不允许伪装拒绝重开任务。
    // 【怎么算红】不复查findByRequestId就转换错误，存在任务时应非Rejected的断言失败。
    @Test void rejectOnlyProvenNonAcceptance() throws Exception {
        var quote=gateway.quote("IMAGE",input());
        when(submit.submitApproved(any(),any())).thenThrow(new WalletServiceImpl.InsufficientBalanceException());
        assertThrows(GenerationRejectedException.class,()->gateway.submit(1,quote,"approval-1"));
        when(submit.findByRequestId(1L,"approval-1")).thenReturn(task());
        assertThrows(WalletServiceImpl.InsufficientBalanceException.class,()->gateway.submit(1,quote,"approval-1"));
        when(submit.findAcceptedByRequestId(1L,"approval-1")).thenThrow(BusinessException.conflict("处理中"));
        assertThrows(BusinessException.class,()->gateway.submit(1,quote,"approval-1"));
    }
    // 【测什么】正常提交携带稳定requestId和原确认金额，绝不调不受限的submit。
    // 【怎么算红】改为submit或漏掉approved.amount时verify不匹配。
    @Test void submissionCarriesDomainPriceGuard() throws Exception {
        var quote=gateway.quote("IMAGE",input()); when(submit.submitApproved(any(),any())).thenReturn(task());
        assertEquals("tsk_test",gateway.submit(1,quote,"approval-1"));
        verify(submit).submitApproved(argThat(r->r.userId()==1 && "approval-1".equals(r.requestId()) && r.imageUrls().isEmpty()),argThat(p->p.amount().compareTo(BigDecimal.ONE)==0 && "CNY".equals(p.currency())));
        verify(submit,never()).submit(any());
    }
    // 【测什么】任务投影只暴露受保护路径；屏蔽、过期、其他用户均不能获得媒体地址。
    // 【怎么算红】去掉moderation/expiry/owner检查，URL或异常断言失败。
    @Test void mediaProjectionIsOwnerScopedAndPolicyAware() {
        var task=task(); when(tasks.getOne(any(),eq(false))).thenReturn(task);
        assertEquals("/api/agent/media/tsk_test",gateway.read(1,"tsk_test").mediaPath());
        assertThrows(BusinessException.class,()->gateway.read(2,"tsk_test"));
        task.setModerationStatus("BLOCKED");assertNull(gateway.read(1,"tsk_test").mediaPath());assertTrue(gateway.read(1,"tsk_test").blocked());
        task.setModerationStatus("VISIBLE");task.setCreateTime(LocalDateTime.now().minusDays(31));assertNull(gateway.read(1,"tsk_test").mediaPath());assertTrue(gateway.read(1,"tsk_test").expired());
    }
    // 【测什么】两个生成Skill不能走Phase1直接execute绕过确认。
    // 【怎么算红】去掉TaskSkill默认拒绝并直接submit时assertThrows失败。
    @Test void taskSkillsCannotBypassApproval() {
        var context=new AgentContext(1L,"s","t","llm","goal","",List.of(),List.of(),0);
        assertThrows(IllegalStateException.class,()->new ImageGenerationSkill(gateway).execute(context,input()));
        assertThrows(IllegalStateException.class,()->new VideoGenerationSkill(gateway).execute(context,input()));
    }

    // 【测什么】DIRECT提交只用重验的规范素材/时长和用户确认金额；仍调用原domain费用守卫。
    // 【怎么算红】删除DIRECT分支、丢弃引用或调用无报价submit时验证失败。
    @Test void directSubmissionCarriesReferencesAndAudioDuration() throws Exception {
        var params=json.createObjectNode().put("provider","local").put("model","music").put("prompt","sound").put("duration",300).put("ownerId",1);
        params.putArray("references").addObject().put("type","audio").put("url","https://storage.example/ref.mp3");
        var quote=new TaskQuote("local","music","Music","AUDIO",params,BigDecimal.TEN,"CNY","DIRECT");
        when(direct.requote(1,"AUDIO",params)).thenReturn(quote);when(submit.submitApproved(any(),any())).thenReturn(task());
        assertEquals("tsk_test",gateway.submit(1,quote,"approval-direct"));
        verify(submit).submitApproved(argThat(r->r.duration()==300 && r.audioUrls().equals(List.of("https://storage.example/ref.mp3"))
                && r.imageUrls().isEmpty() && r.videoUrls().isEmpty() && "approval-direct".equals(r.requestId())),argThat(p->"AUDIO".equals(p.outputType()) && p.amount().compareTo(BigDecimal.TEN)==0));
        verify(submit,never()).submit(any());
    }

    // 【测什么】音频与图片一样重新校验所属用户、屏蔽和过期，只投影受保护媒体入口。
    // 【怎么算红】AUDIO不投影或绕过屏蔽/过期时断言失败。
    @Test void audioProjectionRetainsMediaPolicy() {
        var task=task();task.setOutputType("AUDIO");task.setArtifactKey("outputs/song.mp3");when(tasks.getOne(any(),eq(false))).thenReturn(task);
        assertEquals("AUDIO",gateway.read(1,"tsk_test").mediaType());assertNotNull(gateway.read(1,"tsk_test").mediaPath());
        assertThrows(BusinessException.class,()->gateway.read(2,"tsk_test"));
        task.setModerationStatus("BLOCKED");assertNull(gateway.read(1,"tsk_test").mediaPath());
        task.setModerationStatus("VISIBLE");task.setCreateTime(LocalDateTime.now().minusDays(31));assertNull(gateway.read(1,"tsk_test").mediaPath());
    }
}
