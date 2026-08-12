package org.example.seedancegenarate.engine;

/**
 * 任务预计完成时间（ETA）能力声明：
 * <ul>
 *   <li>{@link #FULL}：可查提供方真实队列位置（如自建 ComfyUI 的 /queue），
 *       排队中可精确给出「前面还有 N 个」；</li>
 *   <li>{@link #BASIC}：只能基于历史平均耗时做时间估算（如云端 Seedance，
 *       队列不可见）；</li>
 *   <li>{@link #NONE}：不提供任何估算。</li>
 * </ul>
 */
public enum EtaCapability {
    FULL,
    BASIC,
    NONE
}
