package org.example.seedancegenarate.service;

import com.baomidou.mybatisplus.extension.service.IService;
import org.example.seedancegenarate.engine.RemoteStatus;
import org.example.seedancegenarate.entity.VideoTask;
import org.example.seedancegenarate.entity.AsyncJob;

public interface VideoTaskService extends IService<VideoTask> {

    /** 按提供方远端任务 ID 反查任务（回调路由用）。 */
    VideoTask getByProviderTaskId(String provider, String providerTaskId);

    /**
     * 依据引擎归一化后的 {@link RemoteStatus} 轻量落库（poller 调用）：
     * 成功时只入队 TASK_FINALIZE 作业（下载交给 Worker 并行），失败则记录错误信息。
     */
    void updateStatus(VideoTask task, RemoteStatus status) throws Exception;

    /**
     * 终态收尾（TASK_FINALIZE 消费方调用）：下载产物 → OSS → 身份 CAS 落 SUCCESS → 结算 → 事件。
     * {@code expectedTask} 是领取作业后读取的 attempt/provider/phase/retry 快照，迟到作业不得越过它。
     */
    void finalizeTask(VideoTask expectedTask, String remoteVideoUrl) throws Exception;

    /**
     * Worker 版本：下载仍在事务外；最终短事务先续租锁住 job 行，再提交任务终态并完成同一租约。
     */
    void finalizeTask(VideoTask expectedTask, String remoteVideoUrl,
                      AsyncJob lease, long leaseSeconds) throws Exception;

    /**
     * 查找最近终态但缺失对应 SETTLE/RELEASE 流水的任务，供分布式账务补偿。
     *
     * @param excludeIds 已隔离（补不好）的任务，必须在 SQL 里排除——它们 id 最小、
     *                   永远排在 {@code ORDER BY id ASC LIMIT} 的队头，只在循环里跳过是没用的
     */
    java.util.List<VideoTask> findTerminalMissingWalletTransition(int limit, java.util.Collection<Long> excludeIds);
}
