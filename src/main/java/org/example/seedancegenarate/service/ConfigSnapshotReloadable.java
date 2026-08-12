package org.example.seedancegenarate.service;

/**
 * 持有进程内配置快照、可被要求重载的组件。
 * <p>
 * 由失效广播（{@code ConfigInvalidationSubscriber}）与定时兜底重载共同驱动。
 * 实现方自己保证 {@link #reload()} 可被并发调用、失败时保留上一份可用快照
 * ——重载失败不该让业务读到空配置。
 */
public interface ConfigSnapshotReloadable {

    /** 该实现关心的失效类型，取 {@code ConfigInvalidationNotifier} 里的常量。 */
    String snapshotType();

    /** 从库重新载入快照。 */
    void reload();
}
