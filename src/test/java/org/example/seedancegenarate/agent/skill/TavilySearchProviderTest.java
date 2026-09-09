package org.example.seedancegenarate.agent.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class TavilySearchProviderTest {
    ObjectMapper json=new ObjectMapper();
    // 【测什么】真实Spring Bean未配key不发现搜索，有配置才发现；不进行健康探测。
    // 【怎么算红】构造器注入失败或Registry忽略available时断言失败。
    @Test void configuredAndUnconfiguredBeanDiscovery() {
        var runner=new ApplicationContextRunner().withBean(ObjectMapper.class,()->json)
                .withUserConfiguration(TavilySearchProvider.class,WebSearchSkill.class,SkillRegistry.class);
        runner.withPropertyValues("TAVILY_API_KEY=").run(c->{assertNull(c.getStartupFailure());assertTrue(c.getBean(SkillRegistry.class).descriptors().isEmpty());});
        runner.withPropertyValues("TAVILY_API_KEY=fake-test-key").run(c->{assertNull(c.getStartupFailure());assertEquals("web-search",c.getBean(SkillRegistry.class).descriptors().get(0).id());});
    }
    // 【测什么】固定HTTPS端点、只发公开搜索字段且硬限制保留，返回恶意URL被过滤。
    // 【怎么算红】参数加raw_content/图片或返回javascript/local链接时失败。
    @Test void providerRequestAndResponseAreBounded() throws Exception {
        var http=mock(HttpClient.class);HttpResponse<byte[]> response=mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);when(response.body()).thenReturn(("{\"results\":[{\"title\":\"服务\",\"url\":\"https://cma.gov.cn/a\",\"content\":\"摘要\"},{\"title\":\"坏\",\"url\":\"http://127.0.0.1/a\",\"content\":\"坏\"}]}").getBytes());
        when(http.send(any(),any(HttpResponse.BodyHandler.class))).thenReturn(response);
        var provider=new TavilySearchProvider("fake-test-key",json,http);
        var result=provider.search(new SearchProvider.Request("武清 气象",List.of("cma.gov.cn"),"year"));
        assertEquals(1,result.sources().size());
        var capture=org.mockito.ArgumentCaptor.forClass(HttpRequest.class);verify(http,times(1)).send(capture.capture(),any());
        var request=capture.getValue();assertEquals("https://api.tavily.com/search",request.uri().toString());assertEquals(20,request.timeout().orElseThrow().toSeconds());
        var body=new TavilySearchProvider.BoundedBody();request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
            public void onSubscribe(Flow.Subscription s){body.onSubscribe(s);}public void onNext(ByteBuffer b){body.onNext(List.of(b));}
            public void onError(Throwable t){body.onError(t);}public void onComplete(){body.onComplete();}
        });
        var payload=json.readTree(body.getBody().toCompletableFuture().get());
        assertFalse(payload.path("include_raw_content").asBoolean(true));assertFalse(payload.path("include_images").asBoolean(true));
        assertFalse(payload.path("include_answer").asBoolean(true));assertEquals("basic",payload.path("search_depth").asText());assertEquals(5,payload.path("max_results").asInt());
        assertEquals("year",payload.path("time_range").asText());assertEquals("cma.gov.cn",payload.path("include_domains").get(0).asText());assertFalse(payload.toString().contains("fake-test-key"));
    }
    // 【测什么】超时、429有限可重试，认证/配额/坏JSON不可重试；不暴露上游正文。
    // 【怎么算红】把所有IOException归retry或所有HTTP归invalid时失败。
    @Test void errorTaxonomyDoesNotLeakBodies() throws Exception {
        for(int status:new int[]{401,429,432,503,302}) {
            var http=mock(HttpClient.class);HttpResponse<byte[]> response=mock(HttpResponse.class);
            when(response.statusCode()).thenReturn(status);when(response.body()).thenReturn("private-provider-secret".getBytes());
            when(http.send(any(),any(HttpResponse.BodyHandler.class))).thenReturn(response);
            var failure=assertThrows(SearchProvider.Failure.class,()->new TavilySearchProvider("fake",json,http).search(new SearchProvider.Request("气象",List.of(),null)));
            assertEquals(status==429||status==503,failure.retryable());assertFalse(failure.toString().contains("private-provider-secret"));assertNull(failure.getCause());
        }
        var http=mock(HttpClient.class);when(http.send(any(),any(HttpResponse.BodyHandler.class))).thenThrow(new java.io.IOException(new TimeoutException()));
        assertEquals("SEARCH_TIMEOUT",assertThrows(SearchProvider.Failure.class,()->new TavilySearchProvider("fake",json,http).search(new SearchProvider.Request("公开",List.of(),null))).code());
    }
    // 【测什么】headers后body卡住与超限均主动cancel，不能占线程无限等待或无限分配。
    // 【怎么算红】删除body timeout/容量检查时超时等待或cancel断言失败。
    @Test void boundedBodyCancelsStallAndOversize() throws Exception {
        var stalled=new TavilySearchProvider.BoundedBody(25);var subscription=mock(Flow.Subscription.class);stalled.onSubscribe(subscription);
        assertThrows(ExecutionException.class,()->stalled.getBody().toCompletableFuture().get(1,TimeUnit.SECONDS));verify(subscription,timeout(500)).cancel();
        var large=new TavilySearchProvider.BoundedBody();var s=mock(Flow.Subscription.class);large.onSubscribe(s);
        large.onNext(List.of(ByteBuffer.wrap(new byte[256001])));
        assertThrows(ExecutionException.class,()->large.getBody().toCompletableFuture().get());verify(s).cancel();
    }
    // 【测什么】URL/输入边界拒绝凭据、私有地址、原文/未知参数，保留最小公开检索。
    // 【怎么算红】删除safePublicUrl或query/domains校验时assertThrows/assertFalse失败。
    @Test void inputBoundary() {
        for(String url:List.of("javascript:alert(1)","http://127.0.0.1/a","http://[::1]/","http://user:pass@cma.gov.cn/a","http://abc.local/a","http://abc.lan/a"))assertFalse(SearchProvider.safePublicUrl(url));
        assertTrue(SearchProvider.safePublicUrl("https://cma.gov.cn/a"));
        var skill=new WebSearchSkill(mock(SearchProvider.class),json);
        for(String query:List.of("","x".repeat(301),"api_key=fake","person@example.com","联系13812345678"))assertThrows(RuntimeException.class,()->skill.validate(json.createObjectNode().put("query",query)));
        assertDoesNotThrow(()->skill.validate(json.createObjectNode().put("query","武清 气象")));
        assertThrows(RuntimeException.class,()->skill.validate(json.createObjectNode().put("query","公开").put("raw_content",true)));
    }
    // 【测什么】摘要证据与来源精准绑定，模型不能伪造来源s99或声称已核实。
    // 【怎么算红】删validateResult证据映射或TextSkill正文引用全量检查时失败。
    @Test void forgedEvidenceAndUnlistedCitationsAreRejected() {
        var provider=mock(SearchProvider.class);
        when(provider.search(any())).thenReturn(new SearchProvider.Result("fake",List.of(new SearchProvider.Source("公开","https://cma.gov.cn/a","摘要"))));
        var research=new WebSearchSkill(provider,json).execute(null,json.createObjectNode().put("query","公开"));
        assertDoesNotThrow(()->WebSearchSkill.validateResult(research));
        var changed=research.data().deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)changed.path("evidence").get(0)).put("sourceId","s99");
        assertThrows(RuntimeException.class,()->WebSearchSkill.validateResult(new SkillResult("WEB_RESEARCH","资料","摘要",null,changed,null)));
        var gateway=mock(org.example.seedancegenarate.agent.model.AgentModelGateway.class);
        when(gateway.complete(any(),anyString(),anyString(),anyString())).thenReturn("{\"title\":\"脚本\",\"content\":\"真实[s1]，伪造[s99]\",\"citations\":[\"s1\"]}");
        var ref=new org.example.seedancegenarate.agent.model.AgentContext.ArtifactRef("r",1,null);
        var context=new org.example.seedancegenarate.agent.model.AgentContext(1L,"s","t","c","目标",null,List.of(),
                List.of(new org.example.seedancegenarate.agent.model.AgentContext.ArtifactContext("r",1,"WEB_RESEARCH","资料","摘要",research.data())),0);
        var input=json.createObjectNode().put("instruction","脚本");input.set("source",json.valueToTree(ref));
        assertThrows(RuntimeException.class,()->new ScriptGenerationSkill(gateway,json).execute(context,input));
    }
    // 【测什么】Planner选中的研究只投影来源目录，不重复注入完整摘要；Script单次引用仍读取精确资料。
    // 【怎么算红】selectedArtifact恢复完整data时secret-snippet出现在Planner请求。
    @Test void plannerDoesNotRepeatResearchEvidence() {
        var gateway=mock(org.example.seedancegenarate.agent.model.AgentModelGateway.class);
        when(gateway.completeDecision(any(),anyString(),anyString(),any())).thenReturn("{\"decision\":{\"type\":\"RESPOND\",\"text\":\"等待\",\"summary\":null}}");
        var data=json.createObjectNode().put("status","SEARCH_RESULTS");data.putArray("sources").addObject().put("sourceId","s1").put("title","公开").put("snippet","secret-snippet");
        var ref=new org.example.seedancegenarate.agent.model.AgentContext.ArtifactRef("r",1,null);
        var context=new org.example.seedancegenarate.agent.model.AgentContext(1L,"s","t","c","目标",null,List.of(),
                List.of(new org.example.seedancegenarate.agent.model.AgentContext.ArtifactContext("r",1,"WEB_RESEARCH","资料","摘要",data)),0,List.of(),null,ref);
        new org.example.seedancegenarate.agent.model.AgentPlanner(gateway,new SkillRegistry(List.of()),json).decide(context);
        var input=org.mockito.ArgumentCaptor.forClass(String.class);verify(gateway).completeDecision(any(),anyString(),input.capture(),any());
        assertFalse(input.getValue().contains("secret-snippet"));assertTrue(input.getValue().contains("s1"));
    }
}
