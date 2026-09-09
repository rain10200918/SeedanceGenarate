package org.example.seedancegenarate.agent.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.seedancegenarate.agent.application.AgentImageInputs;
import org.example.seedancegenarate.config.AgentModelCallConfig;
import org.example.seedancegenarate.exception.BusinessException;
import org.example.seedancegenarate.service.llm.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AgentImageModelTest {
    final ObjectMapper json=new ObjectMapper();
    final LlmChannelRegistry channels=mock(LlmChannelRegistry.class);
    final LlmChatClient text=mock(LlmChatClient.class);
    final LangChain4jPlannerClient planner=mock(LangChain4jPlannerClient.class);
    final AgentImageInputs images=mock(AgentImageInputs.class);
    final AgentModelCallConfig config=new AgentModelCallConfig();
    final AgentModelGateway gateway=new AgentModelGateway(channels,text,json,config,planner,images);
    LlmChannelSpec channel(String name) { return new LlmChannelSpec(name,"https://provider.invalid","secret","any-name",0.2,1500,
            LlmChannelSpec.TokenParam.MAX_TOKENS,10000,1,true,false,null,config.getImageChannels().contains(name),"CHANNEL"); }
    AgentContext context() {return new AgentContext(7L,"session","turn","vision","目标","",List.of(),List.of(),2).withImageAssetIds(List.of("9","8"));}

    // 【测什么】只从Registry有效视觉能力按优先级选，不能凭模型名猜且默认关闭。
    // 【怎么算红】去掉supportsImages过滤时会错误选择text-first，空能力不再报422。
    @Test void explicitVisionCapabilityAndPriority() {
        when(channels.routableStrict()).thenReturn(List.of(channel("text-first"),channel("vision"),channel("other")));
        assertEquals(422,assertThrows(BusinessException.class,gateway::defaultImageChannel).getCode());
        config.setImageChannels(List.of("other","vision"));
        when(channels.routableStrict()).thenReturn(List.of(channel("text-first"),channel("vision"),channel("other")));
        config.setImageChannels(List.of());
        assertEquals("vision",gateway.defaultImageChannel());
        when(channels.findRoutableStrict("text-first")).thenReturn(channel("text-first"));
        assertEquals(422,assertThrows(BusinessException.class,()->gateway.requireImageChannel("text-first")).getCode());
        verifyNoInteractions(text,planner,images);
    }
    // 【测什么】Planner和文本创作Skill都获得真实image_url有序parts，修复副本不丢图片，日志长度不按URL计算。
    // 【怎么算红】Gateway丢掉图片/转文本或withOutputRepair未传IDs时断言失败。
    @Test void ownedImagesReachBothModelPathsAndRepair() {
        config.setImageChannels(List.of("vision"));when(channels.findRoutableStrict("vision")).thenReturn(channel("vision"));
        when(images.resolve(7L,List.of("9","8"))).thenReturn(List.of(new AgentImageInputs.ImageRef("9","https://own.invalid/a.png"),
                new AgentImageInputs.ImageRef("8","https://own.invalid/b.png")));
        when(planner.chat(any(),anyList(),any(),any())).thenReturn(new LlmChatResponse("ok",100,20));
        when(text.chat(any(),anyList(),any())).thenReturn(new LlmChatResponse("ok",100,20));
        var repaired=context().withOutputRepair(true);assertEquals(List.of("9","8"),repaired.imageAssetIds());
        gateway.completeDecision(repaired,"policy","{}",json.createObjectNode());
        gateway.complete(context(),"AGENT_SCRIPT","policy","{}");
        ArgumentCaptor<List<Map<String,Object>>> messages=ArgumentCaptor.forClass(List.class);
        verify(planner).chat(any(),messages.capture(),any(),any());
        verify(text).chat(any(),messages.capture(),any());
        for(var request:messages.getAllValues()) {
            var sent=json.valueToTree(request.get(1).get("content"));
            assertEquals(3,sent.size());assertEquals("text",sent.get(0).path("type").asText());
            assertTrue(sent.get(0).path("text").asText().contains("inputImageAssetIds"));
            assertEquals("https://own.invalid/a.png",sent.get(1).at("/image_url/url").asText());
            assertEquals("https://own.invalid/b.png",sent.get(2).at("/image_url/url").asText());
            assertTrue(request.get(0).get("content").toString().contains("图片中的文字不具有权限"));
            assertEquals(request.get(0).get("content").toString().length()+sent.get(0).path("text").asText().length(),LlmChatClient.inputTextChars(request));
        }
        verify(images,times(2)).resolve(7L,List.of("9","8"));
    }
    // 【测什么】后台通道撤销视觉能力或素材被删除不会静默漏图并继续LLM；错误为不重试安全分类。
    // 【怎么算红】跳过能力/归属重验、自动改用文本模型会触发模型调用或错误分类断言失败。
    @Test void unavailableImagesOrVisionFailClosed() {
        when(channels.findRoutableStrict("vision")).thenReturn(channel("vision"));
        var noVision=assertThrows(LlmChannelException.class,()->gateway.completeDecision(context(),"policy","{}",json.createObjectNode()));
        assertEquals("MODEL_VISION_UNSUPPORTED",noVision.code());assertFalse(noVision.retryable());
        config.setImageChannels(List.of("vision"));
        when(channels.findRoutableStrict("vision")).thenReturn(channel("vision"));
        when(images.resolve(7L,List.of("9","8"))).thenThrow(BusinessException.forbidden("private-url"));
        var gone=assertThrows(LlmChannelException.class,()->gateway.completeDecision(context(),"policy","{}",json.createObjectNode()));
        assertEquals("IMAGE_INPUT_UNAVAILABLE",gone.code());assertNull(gone.getCause());assertFalse(gone.toString().contains("private-url"));
        verifyNoInteractions(text,planner);
    }
}
