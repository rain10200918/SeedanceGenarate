package org.example.seedancegenarate.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 纯单元测试：验证任务类型由「是否有参考图」×「输出类型」正确派生。
 */
class GenerationModeTest {

    @Test
    void derivesFourModes() {
        assertEquals(GenerationMode.TEXT_TO_VIDEO, GenerationMode.of(false, OutputType.VIDEO));
        assertEquals(GenerationMode.IMAGE_TO_VIDEO, GenerationMode.of(true, OutputType.VIDEO));
        assertEquals(GenerationMode.TEXT_TO_IMAGE, GenerationMode.of(false, OutputType.IMAGE));
        assertEquals(GenerationMode.IMAGE_TO_IMAGE, GenerationMode.of(true, OutputType.IMAGE));
    }
}
