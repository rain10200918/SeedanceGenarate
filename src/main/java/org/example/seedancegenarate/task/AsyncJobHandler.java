package org.example.seedancegenarate.task;

import org.example.seedancegenarate.entity.AsyncJob;

/** 一种持久化作业的单张执行边界；领取、公平派发与续租由 runtime 统一负责。 */
public interface AsyncJobHandler {

    String jobType();

    long leaseSeconds();

    /** Types sharing scarce work (for example Agent LLM calls) share this local capacity group. */
    default String concurrencyGroup() { return jobType(); }

    /** Unlimited types retain existing behavior. Explicit limits reserve at least one other worker slot. */
    default int concurrencyLimit() { return Integer.MAX_VALUE; }

    /** 只处理已持有 fencing 的一张作业；完成/失败必须由实现用该 lease 收口。 */
    void execute(AsyncJob lease) throws Exception;

    default boolean enabled() {
        return true;
    }
}
