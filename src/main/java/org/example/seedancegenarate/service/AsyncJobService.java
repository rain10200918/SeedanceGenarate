package org.example.seedancegenarate.service;

import org.example.seedancegenarate.entity.AsyncJob;

import java.util.List;

/** 持久化异步作业：入队、行级租约领取、完成与重试。 */
public interface AsyncJobService {

    /** 入队；同 (jobType, bizKey) 已存在（任意状态）则跳过，保证业务幂等。 */
    void enqueue(String jobType, String bizKey, String payload);

    /** 延迟入队：available_at = now + delaySeconds 后才可领取（替代 RabbitMQ 延迟消息）。 */
    void enqueueDelayed(String jobType, String bizKey, String payload, long delaySeconds);

    /** 领取一批 READY / 租约过期 RUNNING 作业（行锁 + fencing）。 */
    List<AsyncJob> claimBatch(String jobType, int batchSize, long leaseSeconds);

    /** 查询作业（供对账判断是否存在/状态）。 */
    AsyncJob find(String jobType, String bizKey);

    /** 续租；false 表示已被新 generation 接管或不再 RUNNING。 */
    boolean renew(AsyncJob lease, long leaseSeconds);

    /** 持有当前 token + generation 的 Worker 标记成功。 */
    boolean complete(AsyncJob lease);

    /** 原子增加失败次数；false 表示租约已丢失，不得再改业务终态。 */
    boolean failAndRetry(AsyncJob lease, String error);
}
