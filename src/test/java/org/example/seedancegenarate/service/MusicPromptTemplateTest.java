package org.example.seedancegenarate.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MusicPromptTemplateTest {
    private final PromptTemplateService templates = new PromptTemplateService();

    // 【测什么】真实音乐模板按各请求注入目标秒数，重复解析不串值。
    // 【怎么算红】删掉音乐模板duration占位符，或复用上次渲染结果，目标断言失败。
    @Test void selectedDurationReachesMusicGuidanceWithoutLeakingAcrossRequests() {
        for (int duration : new int[]{30, 120, 300, 120}) {
            var resolved = templates.resolve(new PromptContext("minimax-music3", 0, 0, 0, duration, null));
            assertEquals("prompts/minimax-music3.md", resolved.resourcePath());
            assertFalse(resolved.fallback());
            assertTrue(resolved.guide().contains("目标时长（秒）：" + duration + "。"));
            assertFalse(resolved.guide().contains("{duration}"));
        }
    }

    // 【测什么】未指定时长时不偷偷指定120秒，结构标题和器乐约定保持兼容。
    // 【怎么算红】写死120秒、移除缺省说明或改动两段标题/器乐约定，断言失败。
    @Test void absentDurationRetainsUnspecifiedGuidanceAndExistingOutputContract() {
        var guide = templates.guide(new PromptContext("minimax-music3", 0, 0, 0, null, null));
        assertTrue(guide.contains("目标时长（秒）：。"));
        assertTrue(guide.contains("若目标时长为空"));
        assertFalse(guide.contains("{duration}"));
        assertTrue(guide.contains("1. Caption (English Only):"));
        assertTrue(guide.contains("2. Lyrics (With Tags):"));
        assertTrue(guide.contains("歌词部分仅输出 `[Instrumental]`"));
    }
}
