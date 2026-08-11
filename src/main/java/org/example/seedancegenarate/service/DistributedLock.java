package org.example.seedancegenarate.service;

import java.time.Duration;

/**
 * Redis 分布式锁。用于全局定时任务，保证同一时刻只有一个实例执行。
 * <p>
 * Redis 不可用或锁被占用时返回空；关键扫描任务此时必须跳过（fail-closed），
 * 不能退化为所有实例同时执行。
 */
public interface DistributedLock {

    /**
     * 尝试获取锁。
     *
     * @return 持有句柄（需 close 释放）；未获取到或 Redis 不可用时为空
     */
    AutoCloseable tryLock(String key, Duration ttl);
}
