package org.example.seedancegenarate.exception;

import org.example.seedancegenarate.service.AdmissionResult;

/**
 * 并发已达上限。
 *
 * <h3>为什么消息里要说清是哪一层</h3>
 * 「账号总量满了」要联系我们加量；「你这把 key 只分到 2 席」是他们内部管理员重新分配就行。
 * 说不清的话，企业每次撞限都会来问我们，而其中大部分我们什么都做不了。
 *
 * <h3>为什么是异常类型而不是靠 message 文本认</h3>
 * D-028：按 message 判定只能盖住你当天见过的那一条。这里既要被
 * {@code ApiVideoServiceImpl.toApiException}（对外 API → 429 CONCURRENCY_LIMIT）
 * 又要被 {@code GlobalExceptionHandler}（网页/画布 → Result 429）认出来，
 * 两处都必须按<b>类型</b>匹配。
 */
public class ConcurrencyLimitExceededException extends RuntimeException {

    private final AdmissionResult result;

    public ConcurrencyLimitExceededException(AdmissionResult result) {
        super(describe(result));
        this.result = result;
    }

    private static String describe(AdmissionResult r) {
        if (r.rejectedBy() == AdmissionResult.BY_KEY) {
            return "这把密钥分配到的同时可跑任务数已满（" + r.keyCurrent() + "/" + r.keyLimit()
                    + "），请等一个跑完，或让账号管理员调整分配";
        }
        return "同时进行的任务已达上限（" + r.accountCurrent() + "/" + r.accountLimit()
                + "），等一条完成后再提交";
    }

    /** 1=账号总量 2=这把 key 的份额 */
    public int getRejectedBy() {
        return result.rejectedBy();
    }

    public int getLimit() {
        return result.rejectedBy() == AdmissionResult.BY_KEY ? result.keyLimit() : result.accountLimit();
    }

    public long getCurrent() {
        return result.rejectedBy() == AdmissionResult.BY_KEY ? result.keyCurrent() : result.accountCurrent();
    }
}
