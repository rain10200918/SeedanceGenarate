package org.example.seedancegenarate.service;

/**
 * 一次「占车位」的结果。
 *
 * @param tracked     是否真的进了登记表。false = 该账号不限额，<b>整条 Redis 路径被跳过</b>
 * @param admitted    是否放行。影子模式下即使超限也返回 true，但 {@link #rejectedBy()} 会说实话
 * @param rejectedBy  被谁挡下：{@link #NOT_REJECTED} / {@link #BY_ACCOUNT} / {@link #BY_KEY}
 * @param accountCurrent 账号当前在途数
 * @param accountLimit   账号总量
 * @param keyCurrent  这把 key 当前在途数；key 没设上限时为 0
 * @param keyLimit    这把 key 的份额；<b>-1 = 没设</b>
 */
public record AdmissionResult(boolean tracked, boolean admitted, int rejectedBy,
                              long accountCurrent, int accountLimit,
                              long keyCurrent, int keyLimit) {

    public static final int NOT_REJECTED = 0;
    /** 账号买到的总量满了 —— 客户要找我们加量 */
    public static final int BY_ACCOUNT = 1;
    /** 这把 key 被分到的份额满了 —— 客户内部管理员重新分配就行，不用找我们 */
    public static final int BY_KEY = 2;

    /** 不限额 / 功能关闭：一次 Redis 都没发 */
    public static AdmissionResult skipped() {
        return new AdmissionResult(false, true, NOT_REJECTED, 0, 0, 0, -1);
    }

    /** Redis 异常时的降级：放行并告警。理由见 AdmissionControl 的类注释 */
    public static AdmissionResult degraded(int accountLimit) {
        return new AdmissionResult(false, true, NOT_REJECTED, -1, accountLimit, 0, -1);
    }

    /** 若不是影子模式，这一次是否会被拒 —— 影子期就是靠它看清「会拒谁」 */
    public boolean wouldReject() {
        return rejectedBy != NOT_REJECTED;
    }
}
