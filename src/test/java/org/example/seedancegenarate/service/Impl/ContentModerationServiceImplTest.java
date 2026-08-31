package org.example.seedancegenarate.service.Impl;

import org.example.seedancegenarate.dto.ContentModerationRequest;
import org.example.seedancegenarate.entity.ContentModerationAction;
import org.example.seedancegenarate.entity.VideoTask;
import org.example.seedancegenarate.exception.BusinessException;
import org.example.seedancegenarate.mapper.ContentModerationActionMapper;
import org.example.seedancegenarate.mapper.VideoTaskMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ContentModerationServiceImplTest {

    private VideoTaskMapper taskMapper;
    private ContentModerationActionMapper actionMapper;
    private ContentModerationServiceImpl service;

    @BeforeEach
    void setUp() {
        taskMapper = mock(VideoTaskMapper.class);
        actionMapper = mock(ContentModerationActionMapper.class);
        service = new ContentModerationServiceImpl(taskMapper, actionMapper);
    }

    private VideoTask visibleTask() {
        VideoTask task = new VideoTask();
        task.setId(7L);
        task.setBizTaskId("tsk_7");
        task.setStatus("SUCCESS");
        task.setCostAmount(new BigDecimal("8.80"));
        task.setVideoUrl("tsk_7.mp4");
        task.setArtifactKey("outputs/tsk_7/result.mp4");
        task.setModerationStatus("VISIBLE");
        task.setModerationVersion(0);
        return task;
    }

    @Test
    void blockChangesOnlyModerationSnapshotAndAppendsAudit() {
        // 【测什么】一次真实屏蔽写状态快照+审计，任务终态、扣费和产物定位一字不动
        // 【怎么算红】block 调了通用 updateById/任务状态机，顺手改 SUCCESS、cost、videoUrl 或 artifactKey —— 会退款或永久丢失恢复能力
        VideoTask task = visibleTask();
        when(taskMapper.selectById(7L)).thenReturn(task);
        when(taskMapper.blockContent(eq(7L), eq(0), eq("SEXUAL_CONTENT"), anyString(), eq(99L), any()))
                .thenReturn(1);

        VideoTask result = service.block(7L,
                new ContentModerationRequest(0, "sexual_content", "", "人工复核确认"), 99L);

        assertEquals("BLOCKED", result.getModerationStatus());
        assertEquals(1, result.getModerationVersion());
        assertEquals("SUCCESS", result.getStatus());
        assertEquals(new BigDecimal("8.80"), result.getCostAmount());
        assertEquals("tsk_7.mp4", result.getVideoUrl());
        assertEquals("outputs/tsk_7/result.mp4", result.getArtifactKey());

        ArgumentCaptor<ContentModerationAction> captor = ArgumentCaptor.forClass(ContentModerationAction.class);
        verify(actionMapper).insert(captor.capture());
        assertEquals("BLOCK", captor.getValue().getAction());
        assertEquals("VISIBLE", captor.getValue().getFromStatus());
        assertEquals("BLOCKED", captor.getValue().getToStatus());
        assertEquals(99L, captor.getValue().getOperatorId());
    }

    @Test
    void replayingBlockIsIdempotentAndDoesNotDuplicateAudit() {
        // 【测什么】客户端超时重放屏蔽请求时，已在 BLOCKED 的任务直接返回，不再写第二条动作
        // 【怎么算红】每次请求都 insert action —— 一次点击因网络重试在历史里变成十次，审计不再可信
        VideoTask task = visibleTask();
        task.setModerationStatus("BLOCKED");
        task.setModerationVersion(1);
        when(taskMapper.selectById(7L)).thenReturn(task);

        service.block(7L, new ContentModerationRequest(0, "OTHER", null, null), 99L);

        verify(taskMapper, never()).blockContent(anyLong(), anyInt(), anyString(), anyString(), anyLong(), any());
        verify(actionMapper, never()).insert(any(ContentModerationAction.class));
    }

    @Test
    void staleOppositeOperationReturnsConflictInsteadOfOverwriting() {
        // 【测什么】管理员拿旧 version 屏蔽时，若数据库仍不是目标状态就返回 409
        // 【怎么算红】update=0 仍当成功 —— 两个审核员的页面互相覆盖，UI 显示已屏蔽而数据库其实可见
        VideoTask before = visibleTask();
        VideoTask current = visibleTask();
        current.setModerationVersion(1);
        when(taskMapper.selectById(7L)).thenReturn(before, current);
        when(taskMapper.blockContent(anyLong(), anyInt(), anyString(), anyString(), anyLong(), any()))
                .thenReturn(0);

        BusinessException thrown = assertThrows(BusinessException.class, () -> service.block(7L,
                new ContentModerationRequest(0, "OTHER", null, null), 99L));

        assertEquals(409, thrown.getCode());
        verify(actionMapper, never()).insert(any(ContentModerationAction.class));
    }

    @Test
    void restoreKeepsArtifactAndBillingSoOriginalBecomesAvailableAgain() {
        // 【测什么】恢复只清当前屏蔽原因并追加 RESTORE，原视频、OSS key、成功状态和费用仍在
        // 【怎么算红】屏蔽阶段物理删除或恢复重新生成 —— 原文件不能立即回来，且可能再扣一次钱
        VideoTask task = visibleTask();
        task.setModerationStatus("BLOCKED");
        task.setModerationReasonCode("OTHER");
        task.setModerationMessage("暂不可用");
        task.setModerationVersion(2);
        when(taskMapper.selectById(7L)).thenReturn(task);
        when(taskMapper.restoreContent(eq(7L), eq(2), eq(99L), any())).thenReturn(1);

        VideoTask restored = service.restore(7L,
                new ContentModerationRequest(2, null, null, "复核通过"), 99L);

        assertEquals("VISIBLE", restored.getModerationStatus());
        assertNull(restored.getModerationMessage());
        assertEquals("SUCCESS", restored.getStatus());
        assertEquals(new BigDecimal("8.80"), restored.getCostAmount());
        assertEquals("tsk_7.mp4", restored.getVideoUrl());
        assertEquals("outputs/tsk_7/result.mp4", restored.getArtifactKey());
        verify(actionMapper).insert(argThat((ContentModerationAction a) -> "RESTORE".equals(a.getAction())
                && "BLOCKED".equals(a.getFromStatus()) && "VISIBLE".equals(a.getToStatus())));
    }
}
