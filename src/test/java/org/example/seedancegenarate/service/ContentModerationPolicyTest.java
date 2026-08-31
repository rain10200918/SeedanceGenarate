package org.example.seedancegenarate.service;

import org.example.seedancegenarate.entity.VideoTask;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class ContentModerationPolicyTest {

    private final ContentModerationPolicy policy = new ContentModerationPolicy();

    @Test
    void blockedTaskIsRedactedWithoutChangingGenerationBillingOrArtifactTruth() {
        // 【测什么】用户侧脱敏只拿掉交付 token 和管理员内部字段，成功终态、金额与 OSS 定位保持原样
        // 【怎么算红】实现把 status 改成 BLOCKED、把费用归零或清 artifactKey —— 屏蔽会误触账务/删除语义，恢复也找不回原件
        VideoTask task = new VideoTask();
        task.setStatus("SUCCESS");
        task.setCostAmount(new BigDecimal("12.34"));
        task.setVideoUrl("tsk_x.mp4");
        task.setArtifactKey("outputs/tsk_x/result.mp4");
        task.setModerationStatus("BLOCKED");
        task.setModerationMessage("已屏蔽");
        task.setModeratedBy(9L);
        task.setModerationVersion(3);

        policy.redact(task);

        assertNull(task.getVideoUrl());
        assertNull(task.getModeratedBy());
        assertNull(task.getModerationVersion());
        assertEquals("SUCCESS", task.getStatus());
        assertEquals(new BigDecimal("12.34"), task.getCostAmount());
        assertEquals("outputs/tsk_x/result.mp4", task.getArtifactKey());
        assertEquals("已屏蔽", policy.blockedMessage(task));
    }

    @Test
    void visibleOrLegacyNullStatusStaysDeliverable() {
        // 【测什么】VISIBLE 与迁移前/局部查询得到的 null 审核状态都不能误判成屏蔽
        // 【怎么算红】把“不是 VISIBLE”都当成屏蔽 —— 迁移发布瞬间全站历史作品全部不可看
        VideoTask visible = new VideoTask();
        visible.setModerationStatus("VISIBLE");
        VideoTask legacy = new VideoTask();

        assertFalse(policy.isBlocked(visible));
        assertFalse(policy.isBlocked(legacy));
        assertFalse(policy.isBlocked(null));
    }

    @Test
    void missingUserMessageFallsBackToStableSafeCopy() {
        // 【测什么】历史/异常数据即使没有 moderation_message，也给用户明确且不泄露内部信息的说明
        // 【怎么算红】直接返回 null —— 前端只剩空白占位，用户不知道内容为什么不可用
        VideoTask task = new VideoTask();
        task.setModerationStatus("BLOCKED");
        assertEquals(ContentModerationPolicy.DEFAULT_BLOCKED_MESSAGE, policy.blockedMessage(task));
    }
}
