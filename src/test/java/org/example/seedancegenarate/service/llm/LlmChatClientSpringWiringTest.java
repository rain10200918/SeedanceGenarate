package org.example.seedancegenarate.service.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.seedancegenarate.config.PromptOptimizeConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AutowiredAnnotationBeanPostProcessor;

import java.lang.reflect.Constructor;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 【测什么】LlmChatClient 有两个构造器（生产 / 测试注 HttpClient），Spring 必须能从中选出生产那个。
 * 【怎么算红】去掉生产构造器上的 @Autowired：determineCandidateConstructors 返回 null，
 * 容器退回找无参构造器，启动报 "No default constructor found"（2026-09-03 起后端时真发生过）。
 * <p>
 * 用的就是容器里那段判定逻辑本身，不起 Spring 上下文、不连库。
 */
class LlmChatClientSpringWiringTest {

    @Test
    void springCanPickTheProductionConstructorAmongTwo() {
        Constructor<?>[] candidates = new AutowiredAnnotationBeanPostProcessor()
                .determineCandidateConstructors(LlmChatClient.class, "llmChatClient");

        assertNotNull(candidates, "两个构造器都没标 @Autowired，Spring 会去找无参构造器然后启动失败");
        assertEquals(1, candidates.length, "只能有一个被标成注入用的构造器");
        assertArrayEquals(new Class<?>[]{PromptOptimizeConfig.class, ObjectMapper.class},
                candidates[0].getParameterTypes(), "选中的必须是 (PromptOptimizeConfig, ObjectMapper) 那个");
    }
}
