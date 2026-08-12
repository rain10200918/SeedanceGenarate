package org.example.seedancegenarate.service.Impl;

import org.example.seedancegenarate.config.ConfigCacheProperties;
import org.example.seedancegenarate.engine.VideoEngineRegistry;
import org.example.seedancegenarate.entity.ModelAccess;
import org.example.seedancegenarate.mapper.ModelAccessMapper;
import org.example.seedancegenarate.service.ConfigInvalidationNotifier;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 进程内快照路径（{@code cache.config.enabled=true}）：
 * 命中快照不查库、{@code setOpen} 后本实例立刻可见、重载失败保留上一份、改动会广播。
 */
class ModelAccessCacheTest {

    @Test
    void repeatedIsOpenHitsSnapshotWithoutQueryingDb() {
        ModelAccessMapper mapper = mock(ModelAccessMapper.class);
        ModelAccess disabled = new ModelAccess();
        disabled.setModel("z-image-turbo");
        disabled.setEnabled(false);
        when(mapper.selectList(any())).thenReturn(List.of(disabled));

        ModelAccessServiceImpl service = newService(true, mapper, mock(ConfigInvalidationNotifier.class));
        service.init(); // 启动载入，一次查询

        for (int i = 0; i < 20; i++) {
            assertFalse(service.isOpen("z-image-turbo"));
            assertTrue(service.isOpen("minimax-h3")); // 无覆盖 → 默认开
        }

        // 40 次判断只有启动那一次查询（原先每次判断一条单行查询）
        verify(mapper, times(1)).selectList(any());
    }

    @Test
    void setOpenRefreshesLocalSnapshotImmediately() {
        ModelAccessMapper mapper = mock(ModelAccessMapper.class);
        ConfigInvalidationNotifier notifier = mock(ConfigInvalidationNotifier.class);
        when(mapper.selectList(any())).thenReturn(List.of());
        when(mapper.selectOne(any())).thenReturn(null);

        ModelAccessServiceImpl service = newService(true, mapper, notifier);
        service.init();
        assertTrue(service.isOpen("qwen-image-edit")); // 默认开

        // 管理员关掉它：写库后本实例快照要立刻更新，不等兜底重载
        ModelAccess persisted = new ModelAccess();
        persisted.setModel("qwen-image-edit");
        persisted.setEnabled(false);
        when(mapper.selectList(any())).thenReturn(List.of(persisted));
        service.setOpen("qwen-image-edit", false);

        assertFalse(service.isOpen("qwen-image-edit"));
        // 并且广播给其他实例
        verify(notifier).notifyChanged(eq(ConfigInvalidationNotifier.TYPE_MODEL_ACCESS));
    }

    @Test
    void reloadFailureKeepsPreviousSnapshot() {
        ModelAccessMapper mapper = mock(ModelAccessMapper.class);
        ModelAccess disabled = new ModelAccess();
        disabled.setModel("z-image-turbo");
        disabled.setEnabled(false);
        when(mapper.selectList(any())).thenReturn(List.of(disabled));

        ModelAccessServiceImpl service = newService(true, mapper, mock(ConfigInvalidationNotifier.class));
        service.init();
        assertFalse(service.isOpen("z-image-turbo"));

        // 库挂了：重载失败必须保留上一份快照。若退成空覆盖，这个模型会变成「默认开」——
        // 等于库一抖动就把管理员关掉的模型偷偷放开
        when(mapper.selectList(any())).thenThrow(new RuntimeException("db down"));
        service.reload();

        assertFalse(service.isOpen("z-image-turbo"));
    }

    @Test
    void snapshotTypeMatchesNotifierConstant() {
        ModelAccessMapper mapper = mock(ModelAccessMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of());
        ModelAccessServiceImpl service = newService(true, mapper, mock(ConfigInvalidationNotifier.class));

        // 订阅器按这个字符串路由重载，写错就是「广播了但没人重载」
        assertTrue(ConfigInvalidationNotifier.TYPE_MODEL_ACCESS.equals(service.snapshotType()));
    }

    private ModelAccessServiceImpl newService(boolean defaultOpen, ModelAccessMapper mapper,
                                              ConfigInvalidationNotifier notifier) {
        ConfigCacheProperties properties = new ConfigCacheProperties();
        properties.getConfig().setEnabled(true);
        ModelAccessServiceImpl service = new ModelAccessServiceImpl(
                mock(VideoEngineRegistry.class), mapper, properties, notifier);
        ReflectionTestUtils.setField(service, "defaultOpen", defaultOpen);
        return service;
    }
}
