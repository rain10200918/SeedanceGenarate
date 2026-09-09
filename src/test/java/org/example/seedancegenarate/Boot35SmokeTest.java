package org.example.seedancegenarate;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootVersion;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** Real embedded web server, with external infrastructure disabled rather than contacted. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.address=127.0.0.1",
        "server.servlet.context-path=/",
        "management.server.port=",
        "management.endpoints.web.base-path=/actuator",
        "management.endpoints.web.exposure.include=health",
        "management.health.defaults.enabled=false",
        "management.health.ping.enabled=true",
        "spring.flyway.enabled=false",
        "spring.main.lazy-initialization=true",
        "feature.redis-task-events=false",
        "feature.redis-config-invalidation=false",
        "wechat.pay.enabled=false",
        "alipay.enabled=false"
})
@Import(SeedanceGenarateApplicationTests.ExternalInfrastructureIsolation.class)
class Boot35SmokeTest {
    @LocalServerPort int port;
    @Autowired ConfigurableApplicationContext context;

    // 【测什么】锁定Java17兼容的Boot3.5系列，实际实例化Web/Jackson/MyBatis及Planner适配Bean。
    // 【怎么算红】父版本改回3.3.5，或破坏关键自动配置/Planner构造注入，或漏掉新client的统计切面，此测试必须失败。
    @Test void boots35WithRealFrameworkBeans() throws Exception {
        assertThat(SpringBootVersion.getVersion()).startsWith("3.5.");
        assertThat(context.getBean("requestMappingHandlerMapping", RequestMappingHandlerMapping.class)
                .getHandlerMethods()).isNotEmpty();
        assertThat(context.getBean(SqlSessionFactory.class).getConfiguration().getMappedStatementNames()).isNotEmpty();
        assertThat(context.getBean(ObjectMapper.class).readTree("{\"ok\":true}").path("ok").asBoolean()).isTrue();
        assertThat(context.getBean("agentPlanner")).isNotNull();
        assertThat(context.getBean("agentModelGateway")).isNotNull();
        assertThat(org.springframework.aop.support.AopUtils.isAopProxy(context.getBean("langChain4jPlannerClient"))).isTrue();
    }

    // 【测什么】真正本地Tomcat提供健康端点，匿名访问Agent仍然401，不接触真实数据库或Redis。
    // 【怎么算红】移除WebConfig对/api/**的登录拦截，或破坏Actuator映射，HTTP断言必须失败。
    @Test void servesHealthAndRejectsAnonymousAgent() throws Exception {
        HttpResponse<String> health = get("/actuator/health");
        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(context.getBean(ObjectMapper.class).readTree(health.body()).path("status").asText()).isEqualTo("UP");
        HttpResponse<String> agent = get("/api/agent/conversations");
        assertThat(agent.statusCode()).isEqualTo(401);
        assertThat(agent.body()).contains("请先登录");
    }

    private HttpResponse<String> get(String path) throws Exception {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                        .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
}
