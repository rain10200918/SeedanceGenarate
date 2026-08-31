package org.example.seedancegenarate.task;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.seedancegenarate.config.ConcurrencyProperties;
import org.example.seedancegenarate.config.DistributedLockProperties;
import org.example.seedancegenarate.entity.ApiKey;
import org.example.seedancegenarate.entity.VideoTask;
import org.example.seedancegenarate.mapper.ApiKeyMapper;
import org.example.seedancegenarate.mapper.VideoTaskMapper;
import org.example.seedancegenarate.service.AdmissionControl;
import org.example.seedancegenarate.service.MeteredAccountRegistry;
import org.example.seedancegenarate.service.DistributedLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 并发登记表对账：<b>DB 是事实，Redis 照着改</b>。
 *
 * <h3>它不是兜底，它是主力</h3>
 * 老化窗口有好几个小时 —— 一次 Redis failover 之后靠它恢复，意味着企业要少几路并发一整个下午。
 * 真正把系统拉回一致的是这个任务，所以它跑得很勤（2 秒）。
 * <p>
 * 而且它是<b>唯一双向</b>的机制：漏掉的 ZREM（少发）和漏掉的 ZADD（超发）它都能修。
 * 任何「记录待释放事件」式的方案结构上只能修前者 —— 它对「占位丢了」一无所知。
 *
 * <h3>两条容易写错、且错了不报错的规则</h3>
 * <b>① 先读 Redis，再读 DB。</b> 反过来的话：读完 DB（10 条）之后、读 Redis 之前又 admit 进来一条，
 * Redis 里是 11 条 —— 差集判定它是幽灵，划掉。可那条任务真的在跑，它的槽位白占了，
 * 这就是超发。先读 Redis 则新来的会落到「补登记」那一侧，而它本来就在，ZADD 是幂等 no-op。
 * <p>
 * <b>② 成熟期过滤只加在补登记那一侧。</b> {@code save(task)} 在 {@code acquire()} 之前，
 * 中间有个窗口：DB 已是 PROCESSING 但还没占位。对账撞进这个窗口会替它补登记 ——
 * 等于绕过了门口数人头。所以补登记跳过太年轻的行。
 * 但划掉那一侧<b>绝不能</b>用同一个过滤：刚登记的本来就是新来的，把它们从「实际在跑」里排除，
 * 就会把每一条新登记全部划掉。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConcurrencyReconcileTask {

    private static final Duration LOCK_TTL = Duration.ofSeconds(30);

    /**
     * 补登记的成熟期：只有落库超过这么久还不在登记表里的，才认为是「漏登记」。
     * 覆盖 save→acquire 之间的窗口，取值远大于那几毫秒即可。
     */
    private static final Duration ADD_MATURITY = Duration.ofSeconds(10);

    private final AdmissionControl admissionControl;
    private final ConcurrencyProperties properties;
    private final MeteredAccountRegistry meteredAccountRegistry;
    private final ApiKeyMapper apiKeyMapper;
    private final VideoTaskMapper videoTaskMapper;
    private final DistributedLock distributedLock;
    private final DistributedLockProperties lockProperties;

    /** 「哪些 key 设了份额」这份名单的缓存有效期。改份额是人点按钮的低频动作，不需要 2 秒一查 */
    static final long ACCOUNT_CACHE_MS = 30_000L;
    private volatile Set<Long> limitedKeyCache = Set.of();
    private volatile long limitedKeyCachedAt;

    @Scheduled(fixedDelayString = "${concurrency.reconcile-interval-ms:2000}")
    public void reconcile() {
        if (!properties.isEnabled()) {
            return;
        }
        if (!lockProperties.isEnabled()) {
            reconcileLocked();
            return;
        }
        AutoCloseable lock = distributedLock.tryLock("concurrency-reconcile", LOCK_TTL);
        if (lock == null) {
            return; // 其他实例正在对账，或 Redis 不可用
        }
        try (lock) {
            reconcileLocked();
        } catch (Exception e) {
            log.warn("并发额度对账失败（下一轮继续）: {}", e.getMessage());
        }
    }

    void reconcileLocked() {
        long startMs = System.currentTimeMillis();

        // 两侧要对账的对象都先算出来。
        // 每一侧都是「配了限额的」∪「Redis 里已经有登记的」—— 后者不能省：
        // 某个账号/某把 key 的 DB 侧一条在途都没有、Redis 还挂着幽灵时，
        // 它不会出现在任何 GROUP BY 结果里，只靠前者永远清不掉它。
        Set<Long> accounts = new LinkedHashSet<>(limitedAccounts());
        accounts.addAll(admissionControl.trackedAccounts());
        Set<Long> keys = new LinkedHashSet<>(limitedKeys());
        keys.addAll(admissionControl.trackedKeys());
        if (accounts.isEmpty() && keys.isEmpty()) {
            return; // 没有任何限额生效：一次库都不查（没有企业客户时的常态）
        }

        // ★ 先读 Redis（见类注释规则①）
        Map<Long, Set<Long>> actualByAccount = new HashMap<>();
        for (Long userId : accounts) {
            actualByAccount.put(userId, admissionControl.snapshot(userId));
        }
        Map<Long, Set<Long>> actualByKey = new HashMap<>();
        for (Long keyId : keys) {
            actualByKey.put(keyId, admissionControl.snapshotByKey(keyId));
        }

        // ★ 再读 DB。一条查询同时喂两侧 —— api_key_id 就在结果行里，不用查第二遍
        LocalDateTime maturityCutoff = LocalDateTime.now().minus(ADD_MATURITY);
        Map<Long, Set<Long>> acctFull = new HashMap<>();
        Map<Long, Set<Long>> acctMature = new HashMap<>();
        Map<Long, Set<Long>> keyFull = new HashMap<>();
        Map<Long, Set<Long>> keyMature = new HashMap<>();
        for (VideoTask task : inFlightOf(accounts, keys)) {
            boolean mature = task.getCreateTime() != null && task.getCreateTime().isBefore(maturityCutoff);
            acctFull.computeIfAbsent(task.getUserId(), k -> new LinkedHashSet<>()).add(task.getId());
            if (mature) {
                acctMature.computeIfAbsent(task.getUserId(), k -> new LinkedHashSet<>()).add(task.getId());
            }
            Long keyId = task.getApiKeyId();
            if (keyId != null) {
                keyFull.computeIfAbsent(keyId, k -> new LinkedHashSet<>()).add(task.getId());
                if (mature) {
                    keyMature.computeIfAbsent(keyId, k -> new LinkedHashSet<>()).add(task.getId());
                }
            }
        }

        int[] fixed = {0, 0}; // [补登记, 划掉]
        for (Long userId : accounts) {
            diff(actualByAccount.getOrDefault(userId, Set.of()),
                    acctFull.getOrDefault(userId, Set.of()),
                    acctMature.getOrDefault(userId, Set.of()),
                    ghosts -> admissionControl.removeAll(userId, ghosts),
                    missing -> admissionControl.addAll(userId, missing),
                    fixed);
        }
        for (Long keyId : keys) {
            diff(actualByKey.getOrDefault(keyId, Set.of()),
                    keyFull.getOrDefault(keyId, Set.of()),
                    keyMature.getOrDefault(keyId, Set.of()),
                    ghosts -> admissionControl.removeAllByKey(keyId, ghosts),
                    missing -> admissionControl.addAllByKey(keyId, missing),
                    fixed);
        }

        long tookMs = System.currentTimeMillis() - startMs;
        if (fixed[0] > 0 || fixed[1] > 0) {
            // 长期为 0 = 正常路径可靠，不需要再加机制；持续非 0 才值得去查是哪条路在漏
            log.info("并发额度对账: 账号={}, key={}, 补登记={}, 划掉={}, 耗时={}ms",
                    accounts.size(), keys.size(), fixed[0], fixed[1], tookMs);
        } else if (tookMs > LOCK_TTL.toMillis() / 2) {
            log.warn("并发额度对账耗时逼近锁租约: 账号={}, key={}, 耗时={}ms, 租约={}ms",
                    accounts.size(), keys.size(), tookMs, LOCK_TTL.toMillis());
        }
    }

    /**
     * 一个桶的双向集合差。
     * <p>
     * <b>划掉用全量 {@code allInFlight}，补登记用成熟的 {@code mature}</b> —— 这个不对称是有意的，
     * 见类注释规则②。两侧用同一个集合会导致：要么新任务被绕过限额补进来，
     * 要么每一条新登记被立刻划掉（限额恒等于失效）。
     */
    private void diff(Set<Long> have, Set<Long> allInFlight, Set<Long> mature,
                      java.util.function.Consumer<List<Long>> remove,
                      java.util.function.Consumer<List<Long>> add,
                      int[] fixed) {
        List<Long> ghosts = new ArrayList<>();
        for (Long id : have) {
            if (!allInFlight.contains(id)) {
                ghosts.add(id);
            }
        }
        List<Long> missing = new ArrayList<>();
        for (Long id : mature) {
            if (!have.contains(id)) {
                missing.add(id);
            }
        }
        if (!ghosts.isEmpty()) {
            remove.accept(ghosts);
            fixed[1] += ghosts.size();
        }
        if (!missing.isEmpty()) {
            add.accept(missing);
            fixed[0] += missing.size();
        }
    }

    /**
     * 配了席位的账号 —— 和 API 限流拦截器<b>共用同一份名单</b>
     * （{@link MeteredAccountRegistry}，缓存与失效的理由都在那里）。
     * <p>
     * 各查各的话会漂成两套口径，症状是「对账在维护这个账号的登记表，限流却当它是个人用户」，
     * 两边都不报错。
     */
    private Set<Long> limitedAccounts() {
        return meteredAccountRegistry.accountIds();
    }

    /** 设了份额的 key。缓存理由同 {@link MeteredAccountRegistry}，改份额也是低频人工动作 */
    private Set<Long> limitedKeys() {
        long now = System.currentTimeMillis();
        if (now - limitedKeyCachedAt < ACCOUNT_CACHE_MS && limitedKeyCachedAt > 0) {
            return limitedKeyCache;
        }
        Set<Long> fresh = new LinkedHashSet<>();
        for (ApiKey key : apiKeyMapper.selectList(Wrappers.<ApiKey>lambdaQuery()
                .select(ApiKey::getId)
                .isNotNull(ApiKey::getMaxConcurrency))) {
            fresh.add(key.getId());
        }
        limitedKeyCache = fresh;
        limitedKeyCachedAt = now;
        return fresh;
    }

    /**
     * 这些账号 / 这些 key 当前在跑的任务。走 idx_vt_status_user，只取四列。
     * <p>
     * 为什么是 OR 而不是只按账号：一把 key 可以设了份额、而它的属主账号没有任何限额 ——
     * 那个账号不在 accounts 里，只按账号查会漏掉这把 key 的全部在途任务，
     * 于是对账每轮都把它们当「幽灵」划掉，这把 key 的份额永远显示 0。
     * <p>
     * 两个集合都必须判空后再拼条件：{@code IN ()} 要么是非法 SQL，要么被 MyBatis-Plus
     * 整条跳过 —— 后者更糟，等于每 2 秒把<b>全站所有在途任务</b>捞一遍。
     */
    private List<VideoTask> inFlightOf(Set<Long> accounts, Set<Long> keys) {
        if (accounts.isEmpty() && keys.isEmpty()) {
            return List.of();
        }
        return videoTaskMapper.selectList(Wrappers.<VideoTask>lambdaQuery()
                .select(VideoTask::getId, VideoTask::getUserId, VideoTask::getApiKeyId,
                        VideoTask::getCreateTime)
                .eq(VideoTask::getStatus, "PROCESSING")
                .and(w -> {
                    if (!accounts.isEmpty()) {
                        w.in(VideoTask::getUserId, accounts);
                    }
                    if (!keys.isEmpty()) {
                        if (!accounts.isEmpty()) {
                            w.or();
                        }
                        w.in(VideoTask::getApiKeyId, keys);
                    }
                }));
    }
}
