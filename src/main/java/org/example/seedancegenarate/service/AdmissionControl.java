package org.example.seedancegenarate.service;

import java.util.List;
import java.util.Set;

/**
 * 在途并发的「登记表」—— Redis 里每个限额账号一个 ZSET，member = task.id，score = 登记时刻。
 *
 * <h3>它不是真相</h3>
 * 真相永远是 {@code video_task.status='PROCESSING'}。这里只是一份为了「快速数数」而存在的派生索引，
 * 对不上的时候<b>照着 DB 改这里，永远不反过来</b>（{@code ConcurrencyReconcileTask}）。
 * 所以它丢了不会错账，只会让某个企业短暂少几路并发。
 *
 * <h3>为什么 Redis 出错时是放行而不是拒绝</h3>
 * 这一层是<b>商业约定</b>，不是安全带：真正的止损是钱包（MySQL 事务，不受 Redis 影响）。
 * 而且 Redis 整个挂掉时，上游 {@code ApiKeyRateLimitInterceptor} 已经 fail-closed 把请求全拒了，
 * 根本走不到这里 —— 在这儿再 fail-closed 一次，只会在「Redis 半死不活」时把还能服务的客户也打死。
 * 对账会在下一轮把漏掉的登记补上，所以后果是「这一次没检查」，不是「永久超发」。
 *
 * <h3>为什么用 ZSET 而不是计数器</h3>
 * 裸计数器漏掉一次减法就只增不减，最后企业<b>永久锁死</b>（付了钱一条都提交不了）。
 * ZSET 带时间戳，漏掉的 ZREM 会在老化窗口后自己过期 —— 故障自愈，不累积成事故。
 * 而且 member 是 task.id，才能和 DB 算集合差；计数器只能覆盖写，覆盖写会抹掉这一轮新提交的。
 */
public interface AdmissionControl {

    /**
     * 占一个车位。
     * <p>
     * {@code limit.unlimited()} 时<b>一次 Redis 都不发</b>，直接返回 {@link AdmissionResult#skipped()} ——
     * 个人用户走的就是这条，行为与改动前逐字相同。
     * <p>
     * 对同一个 taskId 重复调用是幂等的（不会占两个位）。
     */
    AdmissionResult acquire(Long userId, Long taskId, Long apiKeyId, ConcurrencyLimit limit);

    /**
     * 还车位。best-effort：失败只记日志，交给对账收尾。
     * <p>
     * <b>刻意不判「这个账号有没有限额」</b>：判了就要在终态路径上多读一次 app_user，
     * 而且档位是可以在任务在途期间被管理员加上的 —— 那时按「提交时不限额」跳过释放，
     * 会留下一个只能等老化的幽灵。ZREM 不存在的 key 是 O(1)，不值得为它引入这个漏洞。
     */
    void releaseQuietly(Long userId, Long taskId, Long apiKeyId);

    /** 对账用：读出某账号登记表里的全部 taskId */
    Set<Long> snapshot(Long userId);

    /** 对账用：读出某把 key 份额登记表里的全部 taskId */
    Set<Long> snapshotByKey(Long apiKeyId);

    /** 对账用：补登记（DB 有、登记表没有） */
    void addAll(Long userId, List<Long> taskIds);

    void addAllByKey(Long apiKeyId, List<Long> taskIds);

    /** 对账用：划掉（登记表有、DB 没有） */
    void removeAll(Long userId, List<Long> taskIds);

    void removeAllByKey(Long apiKeyId, List<Long> taskIds);

    /** 对账用：扫出所有存在登记表的账号 —— 覆盖「DB 一条都没有、Redis 还挂着幽灵」的账号 */
    Set<Long> trackedAccounts();

    /** 同上，key 份额那一侧 */
    Set<Long> trackedKeys();
}
