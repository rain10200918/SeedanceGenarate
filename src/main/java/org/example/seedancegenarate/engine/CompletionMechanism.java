package org.example.seedancegenarate.engine;

/**
 * 任务完成通知机制（引擎能力声明）：
 * <ul>
 *   <li>{@link #CALLBACK}：事件驱动（epoll 式）——提交时注册回调，完成时提供方主动通知；</li>
 *   <li>{@link #POLL}：轮询驱动（select 式）——只能查询状态，由框架按退避周期轮询。</li>
 * </ul>
 * 每个引擎声明自己的机制，框架统一分流，不感知具体提供方。
 */
public enum CompletionMechanism {
    CALLBACK,
    POLL
}
