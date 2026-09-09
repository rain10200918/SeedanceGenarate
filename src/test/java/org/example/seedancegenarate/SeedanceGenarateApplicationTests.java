package org.example.seedancegenarate;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.scheduling.config.TaskManagementConfigUtils;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;

@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "spring.main.lazy-initialization=true"
})
@Import(SeedanceGenarateApplicationTests.ExternalInfrastructureIsolation.class)
// 本测试没有 Mockito Bean；只保留会真正触发上下文加载的监听器，避免 CI 额外依赖 JVM 动态挂载能力。
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.REPLACE_DEFAULTS
)
class SeedanceGenarateApplicationTests {

    @Test
    void contextLoads() {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ExternalInfrastructureIsolation {

        /** 启动期快照若误触数据库应立即失败并走已有降级，不能连接开发机或 CI 的真实库。 */
        @Bean
        DataSource dataSource() {
            return new AbstractDataSource() {
                @Override
                public Connection getConnection() throws SQLException {
                    throw offlineDatabase();
                }

                @Override
                public Connection getConnection(String username, String password) throws SQLException {
                    throw offlineDatabase();
                }

                private SQLException offlineDatabase() {
                    return new SQLNonTransientConnectionException(
                            "Database access is intentionally disabled for contextLoads");
                }
            };
        }

        @Bean
        static BeanFactoryPostProcessor removeExternalStartupBeans() {
            return beanFactory -> {
                BeanDefinitionRegistry registry = (BeanDefinitionRegistry) beanFactory;
                // Redis Pub/Sub 容器是 SmartLifecycle，会在 refresh 阶段主动建立连接。
                removeIfPresent(registry, "jobAvailableListenerContainer");
                // 生产入口显式 @EnableScheduling，测试属性无法将它关闭；测试侧移除处理器即可。
                removeIfPresent(registry,
                        TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME);
            };
        }

        private static void removeIfPresent(BeanDefinitionRegistry registry, String beanName) {
            if (registry.containsBeanDefinition(beanName)) {
                registry.removeBeanDefinition(beanName);
            }
        }
    }

}
