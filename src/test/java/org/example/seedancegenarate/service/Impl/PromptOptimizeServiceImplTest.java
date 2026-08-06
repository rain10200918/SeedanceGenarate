package org.example.seedancegenarate.service.Impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.seedancegenarate.service.PromptContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 纯单元测试：验证按 model 选择提示词模板、占位注入、默认回退与通用输出铁律。
 * 只调 buildSystemPrompt（不触网、不需要 LLM 配置）。
 */
class PromptOptimizeServiceImplTest {

    private final PromptOptimizeServiceImpl service =
            new PromptOptimizeServiceImpl(null, new ObjectMapper());

    @Test
    void ref2vaTemplateSelectedAndImageCountInjected() {
        String sys = service.buildSystemPrompt(new PromptContext("minimax-h3", 3, 8, "16:9"));

        assertTrue(sys.contains("参考生视频"), "应命中 minimax-h3 专用模板");
        assertTrue(sys.contains("<Picture 3>"), "{imageCount} 应被替换为 3");
        assertFalse(sys.contains("{imageCount}"), "占位符不应残留");
        assertTrue(sys.contains("只输出"), "应追加通用输出铁律");
    }

    @Test
    void unknownModelFallsBackToDefault() {
        String sys = service.buildSystemPrompt(new PromptContext("no-such-model", 0, null, null));

        assertTrue(sys.contains("文生视频"), "未知模型应回退到 default.md");
        assertTrue(sys.contains("只输出"), "仍应有输出铁律");
    }

    @Test
    void nullContextUsesDefaultWithoutError() {
        String sys = service.buildSystemPrompt(null);

        assertTrue(sys.contains("文生视频"));
        assertTrue(sys.contains("只输出"));
    }
}
