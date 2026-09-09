package org.example.seedancegenarate.agent.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.seedancegenarate.config.AgentModelCallConfig;
import org.example.seedancegenarate.service.llm.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentModelBindingTest {
    final LlmChannelRegistry registry=mock(LlmChannelRegistry.class);
    final LlmChatClient text=mock(LlmChatClient.class);
    final LangChain4jPlannerClient planner=mock(LangChain4jPlannerClient.class);
    final ObjectMapper json=new ObjectMapper();
    final AgentModelGateway gateway=new AgentModelGateway(registry,text,json,new AgentModelCallConfig(),planner);
    LlmChannelSpec spec(String model,String url,String key) {
        return new LlmChannelSpec("self",url,key,model,0.7,1500,LlmChannelSpec.TokenParam.MAX_TOKENS,10000,1,true,false,null);
    }
    // 【测什么】模型和接口改变绑定变化，轮换API key或调整输出预算不影响绑定，不把秘密保存进运行身份。
    // 【怎么算红】摘要不含model/baseUrl或包含apiKey/options，下面相等/不等断言失败。
    @Test void identityTracksModelAndProviderNotSecretsOrOptions() {
        when(registry.findRoutableStrict("self")).thenReturn(spec("m1","https://one.invalid","secret"));
        String first=gateway.channelBinding("self");assertTrue(first.matches("[a-f0-9]{64}"));
        when(registry.findRoutableStrict("self")).thenReturn(spec("m1","https://one.invalid","rotated").withMaxTokens(4096).withTimeoutMs(300000));
        assertEquals(first,gateway.channelBinding("self"));
        when(registry.findRoutableStrict("self")).thenReturn(spec("m2","https://one.invalid","secret"));
        assertNotEquals(first,gateway.channelBinding("self"));
        when(registry.findRoutableStrict("self")).thenReturn(spec("m1","https://two.invalid","secret"));
        assertNotEquals(first,gateway.channelBinding("self"));
    }
    // 【测什么】Runtime准备与发出请求之间配置变化，Gateway也拦截；修复和图片Context副本保留模型绑定。
    // 【怎么算红】移除Gateway身份复验或Context副本丢绑定，会触发外部client或错误码断言失败。
    @Test void callTimeRecheckRejectsModelChangeOnBothPaths() {
        when(registry.findRoutableStrict("self")).thenReturn(spec("old","https://one.invalid","secret"));
        var context=new AgentContext(1L,"s","t","self","goal",null,List.of(),List.of(),0)
                .withModelBinding(gateway.channelBinding("self")).withImageAssetIds(List.of()).withOutputRepair(true);
        assertNotNull(context.modelBinding());
        when(registry.findRoutableStrict("self")).thenReturn(spec("new","https://one.invalid","secret"));
        var decision=assertThrows(LlmChannelException.class,()->gateway.completeDecision(context,"policy","{}",json.createObjectNode()));
        assertEquals("MODEL_CONFIGURATION_CHANGED",decision.code());assertFalse(decision.retryable());
        var skill=assertThrows(LlmChannelException.class,()->gateway.complete(context,"AGENT_SCRIPT","policy","{}"));
        assertEquals("MODEL_CONFIGURATION_CHANGED",skill.code());verifyNoInteractions(text,planner);
    }
}
