package org.example.seedancegenarate.controller;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 所有产物交付路径必须在签 OSS 地址之前经过内容屏蔽策略。 */
class ContentModerationDeliveryGuardTest {

    @Test
    void everySignedDeliveryPathMentionsTheModerationPolicyBeforeSigning() throws Exception {
        // 【测什么】网页媒体、网页下载、对外 API、画布下游四个签名出口都先判断屏蔽
        // 【怎么算红】任一出口新增/重构时把 isBlocked 放到 createSigned* 之后或整个删掉 —— 被屏蔽原件仍能从那个入口流出
        String web = Files.readString(Path.of(
                "src/main/java/org/example/seedancegenarate/controller/VideoController.java"));
        String api = Files.readString(Path.of(
                "src/main/java/org/example/seedancegenarate/controller/ApiVideoController.java"));
        String canvas = Files.readString(Path.of(
                "src/main/java/org/example/seedancegenarate/service/Impl/CanvasArtifactResolverImpl.java"));

        assertTrue(count(web, "contentModerationPolicy.isBlocked(task)") >= 2,
                "网页播放与下载必须各有一次屏蔽门禁");
        assertTrue(web.indexOf("contentModerationPolicy.isBlocked(task)")
                < web.indexOf("createSignedDownloadUrl"));
        assertTrue(api.indexOf("contentModerationPolicy.isBlocked(task)")
                < api.indexOf("createSignedGetUrl"));
        assertTrue(canvas.indexOf("contentModerationPolicy.isBlocked(task)")
                < canvas.indexOf("createSignedGetUrl"));
    }

    @Test
    void moderationUpdateSqlCannotTouchTaskBillingOrArtifactColumns() throws Exception {
        // 【测什么】屏蔽/恢复的两条 SQL 物理上只更新 moderation_* 列
        // 【怎么算红】未来为了省事把 status/video_url/artifact_key/cost_amount 拼进 SET —— 屏蔽开始退款、丢原件或破坏账本
        String source = Files.readString(Path.of(
                "src/main/java/org/example/seedancegenarate/mapper/VideoTaskMapper.java"));
        String block = between(source, "@Update(\"UPDATE video_task SET moderation_status = 'BLOCKED'", "int blockContent");
        String restore = between(source, "@Update(\"UPDATE video_task SET moderation_status = 'VISIBLE'", "int restoreContent");
        for (String sql : new String[]{block, restore}) {
            String setClause = sql.substring(0, sql.indexOf("WHERE"));
            assertFalse(setClause.contains("status = 'SUCCESS'"));
            assertFalse(setClause.contains("video_url"));
            assertFalse(setClause.contains("artifact_key"));
            assertFalse(setClause.contains("cost_amount"));
        }
    }

    private int count(String text, String needle) {
        return (text.length() - text.replace(needle, "").length()) / needle.length();
    }

    private String between(String text, String start, String end) {
        int from = text.indexOf(start);
        int to = text.indexOf(end, from);
        assertTrue(from >= 0 && to > from, "未找到结构片段: " + start);
        return text.substring(from, to);
    }
}
