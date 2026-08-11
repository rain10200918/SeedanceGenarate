package org.example.seedancegenarate.service;

import org.example.seedancegenarate.entity.AsyncJob;

import java.util.List;

/** 持久化异步作业：入队、行级租约领取、完成与重试。 */
public interface AsyncJobService {

    /** 入队；同 (jobType, bizKey) 已存在（任意状态）则跳过，保证业务幂等。 */
    void enqueue(String jobType, String bizKey, String payload);

    /** 领取一批 READY 作业（行级 CAS，多 Worker 并发安全）。 */
    List<AsyncJob> claimBatch(String jobType, int batchSize, long leaseSeconds);

    /** 领取单个作业（供对账补跑等场景）。 */
    AsyncJob claim(String jobType, String bizKey, long leaseSeconds);

    /** 查询作业（供对账判断是否存在/状态）。 */
    AsyncJob find(String jobType, String bizKey);

    /** 持有租约的 Worker 标记成功。 */
    void complete(Long jobId, String leaseToken);

    /** 失败：未超次数按退避回 READY，超过则进入 DEAD。 */
    void failAndRetry(Long jobId, String leaseToken, String error);
}
