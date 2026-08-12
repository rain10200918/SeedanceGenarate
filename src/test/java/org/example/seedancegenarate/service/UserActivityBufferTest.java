package org.example.seedancegenarate.service;

import org.example.seedancegenarate.config.ConfigCacheProperties;
import org.example.seedancegenarate.entity.AppUser;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class UserActivityBufferTest {

    @Test
    void mergesRepeatedActivityOfSameUserIntoSingleWrite() {
        AppUserService appUserService = mock(AppUserService.class);
        UserActivityBuffer buffer = new UserActivityBuffer(appUserService, properties(true));

        // 同一用户连续 10 次请求（原先是 10 次 UPDATE）
        for (int i = 0; i < 10; i++) {
            buffer.record(1L, "1.2.3.4", "杭州", "查看任务列表 " + i);
        }
        // 进缓冲期间一次库都不碰
        verify(appUserService, never()).updateById(any());

        buffer.flush();

        // 合并成一次，且写的是最后那条
        ArgumentCaptor<AppUser> captor = ArgumentCaptor.forClass(AppUser.class);
        verify(appUserService, times(1)).updateById(captor.capture());
        assertEquals("查看任务列表 9", captor.getValue().getLastOperation());
        assertEquals(1L, captor.getValue().getId());
    }

    @Test
    void flushWritesOncePerUser() {
        AppUserService appUserService = mock(AppUserService.class);
        UserActivityBuffer buffer = new UserActivityBuffer(appUserService, properties(true));

        buffer.record(1L, "1.1.1.1", "杭州", "查看任务列表");
        buffer.record(2L, "2.2.2.2", "北京", "提交视频生成");
        buffer.record(1L, "1.1.1.1", "杭州", "刷新任务状态");

        assertEquals(2, buffer.pendingCount());
        buffer.flush();

        verify(appUserService, times(2)).updateById(any());
        assertEquals(0, buffer.pendingCount());
    }

    @Test
    void flushOnEmptyBufferTouchesNothing() {
        AppUserService appUserService = mock(AppUserService.class);
        UserActivityBuffer buffer = new UserActivityBuffer(appUserService, properties(true));

        buffer.flush();

        verify(appUserService, never()).updateById(any());
    }

    @Test
    void writesThroughImmediatelyWhenDisabled() {
        AppUserService appUserService = mock(AppUserService.class);
        // 开关关掉 → 回退到「每次记录直写」的原行为
        UserActivityBuffer buffer = new UserActivityBuffer(appUserService, properties(false));

        buffer.record(1L, "1.2.3.4", "杭州", "查看任务列表");

        verify(appUserService, times(1)).updateById(any());
        assertEquals(0, buffer.pendingCount());
    }

    @Test
    void writeFailureDoesNotPropagate() {
        AppUserService appUserService = mock(AppUserService.class);
        org.mockito.Mockito.when(appUserService.updateById(any()))
                .thenThrow(new RuntimeException("db down"));
        UserActivityBuffer buffer = new UserActivityBuffer(appUserService, properties(true));

        buffer.record(1L, "1.2.3.4", "杭州", "查看任务列表");
        // 观测数据写失败不该冒泡（会打断定时任务后续轮次）
        buffer.flush();

        assertEquals(0, buffer.pendingCount());
    }

    @Test
    void nullUserIdIsIgnored() {
        AppUserService appUserService = mock(AppUserService.class);
        UserActivityBuffer buffer = new UserActivityBuffer(appUserService, properties(true));

        buffer.record(null, "1.2.3.4", "杭州", "查看任务列表");

        assertEquals(0, buffer.pendingCount());
        verify(appUserService, never()).updateById(any());
    }

    private ConfigCacheProperties properties(boolean activityEnabled) {
        ConfigCacheProperties properties = new ConfigCacheProperties();
        properties.getActivity().setEnabled(activityEnabled);
        return properties;
    }
}
