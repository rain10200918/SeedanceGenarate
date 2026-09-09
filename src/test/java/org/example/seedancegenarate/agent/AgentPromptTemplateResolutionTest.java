package org.example.seedancegenarate.agent;
import org.example.seedancegenarate.service.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AgentPromptTemplateResolutionTest {
    // 【测什么】目标生成模型精确命中真实同名模板；未知模型明确回落default，不猜别名或读取路径。
    // 【怎么算红】做别名猜测/忽略路径校验/错载模板，来源与fallback及兼容guide断言失败。
    @Test void resolvesExactModelAndReportsFallbackWithoutGuessingAliases() {
        var templates=new PromptTemplateService();
        var context=new PromptContext("minimax-h3-t2v-hd",0,0,0,5,"16:9");
        var exact=templates.resolve(context);assertEquals("prompts/minimax-h3-t2v-hd.md",exact.resourcePath());assertFalse(exact.fallback());
        assertEquals(templates.guide(context),exact.guide());assertTrue(exact.guide().contains("MiniMax-H3 文生视频高清版"));
        for(String model:new String[]{"minimax-h3-t2v-hd-unregistered","../minimax-h3-t2v-hd","UNKNOWN_MODEL"}) {
            var fallback=templates.resolve(new PromptContext(model,0,0,0,5,"16:9"));
            assertTrue(fallback.fallback());assertEquals("prompts/default.md",fallback.resourcePath());
        }
    }
}
