package org.example.seedancegenarate.service.Impl;

import org.example.seedancegenarate.engine.VideoEngineRegistry;
import org.example.seedancegenarate.entity.ModelAccess;
import org.example.seedancegenarate.mapper.ModelAccessMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 纯单元测试（Mockito mock mapper，不连库）：验证开关裁决逻辑
 * ——默认值、显式覆盖优先、空 model 不拦截、setOpen 的 insert/update 分支。
 */
class ModelAccessServiceImplTest {

    private ModelAccessServiceImpl newService(boolean defaultOpen, ModelAccessMapper mapper) {
        ModelAccessServiceImpl service = new ModelAccessServiceImpl(mock(VideoEngineRegistry.class), mapper);
        ReflectionTestUtils.setField(service, "defaultOpen", defaultOpen);
        return service;
    }

    @Test
    void isOpen_noRow_followsDefault() {
        ModelAccessMapper mapper = mock(ModelAccessMapper.class);
        when(mapper.selectOne(any())).thenReturn(null);
        assertTrue(newService(true, mapper).isOpen("minimax-h3-accel"));
        assertFalse(newService(false, mapper).isOpen("minimax-h3-accel"));
    }

    @Test
    void isOpen_explicitOverride_winsOverDefault() {
        ModelAccessMapper mapper = mock(ModelAccessMapper.class);

        ModelAccess disabled = new ModelAccess();
        disabled.setModel("z-image-turbo");
        disabled.setEnabled(false);
        when(mapper.selectOne(any())).thenReturn(disabled);
        assertFalse(newService(true, mapper).isOpen("z-image-turbo")); // 默认开，显式关胜出

        ModelAccess enabled = new ModelAccess();
        enabled.setModel("z-image-turbo");
        enabled.setEnabled(true);
        when(mapper.selectOne(any())).thenReturn(enabled);
        assertTrue(newService(false, mapper).isOpen("z-image-turbo")); // 默认关，显式开胜出
    }

    @Test
    void isOpen_blankModel_notGated_andNoQuery() {
        ModelAccessMapper mapper = mock(ModelAccessMapper.class);
        assertTrue(newService(false, mapper).isOpen(null));
        assertTrue(newService(false, mapper).isOpen("  "));
        verify(mapper, never()).selectOne(any());
    }

    @Test
    void setOpen_noExisting_inserts() {
        ModelAccessMapper mapper = mock(ModelAccessMapper.class);
        when(mapper.selectOne(any())).thenReturn(null);
        newService(true, mapper).setOpen("qwen-image-edit", false);
        verify(mapper).insert(any(ModelAccess.class));
        verify(mapper, never()).updateById(any(ModelAccess.class));
    }

    @Test
    void setOpen_existing_updates() {
        ModelAccessMapper mapper = mock(ModelAccessMapper.class);
        ModelAccess existing = new ModelAccess();
        existing.setId(1L);
        existing.setModel("qwen-image-edit");
        existing.setEnabled(true);
        when(mapper.selectOne(any())).thenReturn(existing);
        newService(true, mapper).setOpen("qwen-image-edit", false);
        verify(mapper).updateById(any(ModelAccess.class));
        verify(mapper, never()).insert(any(ModelAccess.class));
    }
}
