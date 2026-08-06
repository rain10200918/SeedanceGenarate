package org.example.seedancegenarate.engine;

/**
 * 归一化后的任务状态，屏蔽各提供方的私有返回格式。
 */
public enum GenerationState {
    PROCESSING,
    SUCCESS,
    FAILED
}
