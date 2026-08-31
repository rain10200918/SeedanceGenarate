package org.example.seedancegenarate.service.Impl;

import lombok.extern.slf4j.Slf4j;
import org.example.seedancegenarate.config.ConcurrencyProperties;
import org.example.seedancegenarate.service.AdmissionControl;
import org.example.seedancegenarate.service.AdmissionResult;
import org.example.seedancegenarate.service.ConcurrencyLimit;
import org.example.seedancegenarate.service.ConcurrencyPolicy;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
public class AdmissionControlImpl implements AdmissionControl {

    /**
     * 「淘汰超龄 → 重入检查 → 数 → 判 → 登记」一次原子执行。
     * <p>
     * 分开写就有竞态：两个实例同时读到 49 < 50，两个都放行 —— 3000 人的企业下这必然发生。
     * <p>
     * 影子模式下<b>超限也照样登记</b>：不登记的话计数会失真，影子期看到的分布是错的，
     * 而看清真实分布正是跑影子的唯一目的。
     */
    /**
     * 「淘汰超龄 → 重入检查 → 数两个桶 → 判 → 两个桶一起登记」<b>一次原子执行</b>。
     * <p>
     * 分开写就有竞态：两个实例同时读到 49 &lt; 50，两个都放行 —— 3000 人的企业下这必然发生。
     * 两个桶也必须在同一段脚本里判完：先判账号再判 key 的话，中间那一瞬别人可能刚好占满，
     * 于是出现「账号桶记了、key 桶没记」的半截状态，两边计数从此对不上。
     *
     * <h3>两个桶分别是什么</h3>
     * KEYS[1] = 账号桶：企业买到的<b>总量</b>。所有 key、网页、画布的任务都记在这里。<br>
     * KEYS[2] = key 桶：企业内部给这把 key <b>分配</b>的份额。只在这把 key 设了上限时才用到
     * （{@code keyCapacity &lt; 0} 表示没设，整段跳过）。
     * <p>
     * 影子模式下<b>超限也照样登记</b>：不登记的话计数会失真，影子期看到的分布是错的，
     * 而看清真实分布正是跑影子的唯一目的。
     */
    private static final DefaultRedisScript<List> ACQUIRE = new DefaultRedisScript<>("""
            local acctCap     = tonumber(ARGV[1])
            local keyCap      = tonumber(ARGV[2])
            local agingMillis = tonumber(ARGV[3])
            local member      = ARGV[4]
            local ttlMillis   = tonumber(ARGV[5])
            local shadow      = tonumber(ARGV[6])
            local time = redis.call('TIME')
            local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
            local cutoff = '(' .. (now - agingMillis)

            -- 老化：漏掉的 ZREM 在这里自愈，不会累积成永久幽灵。严格早于边界才淘汰。
            redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', cutoff)
            if keyCap >= 0 then
                redis.call('ZREMRANGEBYSCORE', KEYS[2], '-inf', cutoff)
            end

            -- 重入：同一个 task 重复 acquire 不该占两个位
            if redis.call('ZSCORE', KEYS[1], member) then
                redis.call('PEXPIRE', KEYS[1], ttlMillis)
                local kc = 0
                if keyCap >= 0 then
                    redis.call('PEXPIRE', KEYS[2], ttlMillis)
                    kc = redis.call('ZCARD', KEYS[2])
                end
                return {1, 0, redis.call('ZCARD', KEYS[1]), kc, 0}
            end

            local acctCur = redis.call('ZCARD', KEYS[1])
            local keyCur  = 0
            if keyCap >= 0 then
                keyCur = redis.call('ZCARD', KEYS[2])
            end

            -- 拒绝原因要分清：0=没拒 1=账号总量满 2=这把 key 的份额满。
            -- 客户看到「账号 50 席满了」和「你这把 key 只分到 2 席」，该找谁完全不同——
            -- 前者要联系我们加量，后者是他们内部管理员重新分配。
            local reject = 0
            if acctCur >= acctCap then
                reject = 1
            elseif keyCap >= 0 and keyCur >= keyCap then
                reject = 2
            end

            if reject == 0 or shadow == 1 then
                redis.call('ZADD', KEYS[1], now, member)
                redis.call('PEXPIRE', KEYS[1], ttlMillis)
                acctCur = redis.call('ZCARD', KEYS[1])
                if keyCap >= 0 then
                    redis.call('ZADD', KEYS[2], now, member)
                    redis.call('PEXPIRE', KEYS[2], ttlMillis)
                    keyCur = redis.call('ZCARD', KEYS[2])
                end
            end
            local admitted = 1
            if reject > 0 and shadow == 0 then
                admitted = 0
            end
            return {admitted, reject, acctCur, keyCur, reject}
            """, List.class);

    private final StringRedisTemplate redis;
    private final ConcurrencyProperties properties;
    private final ConcurrencyPolicy policy;

    public AdmissionControlImpl(StringRedisTemplate redis,
                                ConcurrencyProperties properties,
                                ConcurrencyPolicy policy) {
        this.redis = redis;
        this.properties = properties;
        this.policy = policy;
    }

    @Override
    public AdmissionResult acquire(Long userId, Long taskId, Long apiKeyId, ConcurrencyLimit limit) {
        if (limit == null || limit.unlimited() || userId == null || taskId == null) {
            return AdmissionResult.skipped();
        }
        // key 桶只在「这把 key 设了份额」时存在。绝大多数请求（网页、画布、没设份额的 key）
        // 走的还是单桶，一次 Redis 都不多花。
        int keyCap = limit.keyCapForScript();
        boolean perKey = keyCap >= 0 && apiKeyId != null;
        long agingMillis = policy.agingWindowSeconds() * 1000L;
        try {
            List<?> raw = redis.execute(ACQUIRE,
                    List.of(accountKey(userId), perKey ? keyKey(apiKeyId) : accountKey(userId)),
                    String.valueOf(limit.accountCapForScript()),
                    String.valueOf(perKey ? keyCap : -1),
                    String.valueOf(agingMillis),
                    String.valueOf(taskId),
                    String.valueOf(agingMillis * 2),
                    properties.isShadow() ? "1" : "0");
            if (raw == null || raw.size() < 5) {
                throw new IllegalStateException("并发额度脚本返回异常: " + raw);
            }
            boolean admitted = asLong(raw.get(0)) == 1;
            int rejectedBy = (int) asLong(raw.get(1));
            long acctCur = asLong(raw.get(2));
            long keyCur = asLong(raw.get(3));
            if (rejectedBy != AdmissionResult.NOT_REJECTED) {
                log.info("{}并发达上限（{}）: userId={}, apiKeyId={}, taskId={}, 账号={}/{}, key={}/{}",
                        properties.isShadow() ? "[影子] " : "",
                        rejectedBy == AdmissionResult.BY_KEY ? "这把 key 的份额" : "账号总量",
                        userId, apiKeyId, taskId, acctCur, limit.max(),
                        perKey ? String.valueOf(keyCur) : "-", perKey ? String.valueOf(keyCap) : "-");
            }
            return new AdmissionResult(true, admitted, rejectedBy,
                    acctCur, limit.max(), keyCur, perKey ? keyCap : -1);
        } catch (Exception e) {
            // 放行而不是拒绝：见 AdmissionControl 类注释。对账下一轮会把这条补登记。
            log.warn("并发额度不可用，本次放行（对账会补登记）: userId={}, taskId={}, reason={}",
                    userId, taskId, e.getMessage());
            return AdmissionResult.degraded(limit.max());
        }
    }

    @Override
    public void releaseQuietly(Long userId, Long taskId, Long apiKeyId) {
        if (!properties.isEnabled() || userId == null || taskId == null) {
            return;
        }
        try {
            String member = String.valueOf(taskId);
            redis.opsForZSet().remove(accountKey(userId), member);
            // key 桶无条件也放一次：这里刻意不判「这把 key 有没有设份额」——
            // 份额是可以在任务在途期间被加上或去掉的，按提交时的状态决定放不放，
            // 会留下一个只能等老化的幽灵。ZREM 不存在的 key 是 O(1)。
            if (apiKeyId != null) {
                redis.opsForZSet().remove(keyKey(apiKeyId), member);
            }
        } catch (Exception e) {
            log.warn("归还并发槽位失败，等待对账修正: userId={}, taskId={}, reason={}",
                    userId, taskId, e.getMessage());
        }
    }

    @Override
    public Set<Long> snapshot(Long userId) {
        return snapshotOf(accountKey(userId));
    }

    @Override
    public Set<Long> snapshotByKey(Long apiKeyId) {
        return snapshotOf(keyKey(apiKeyId));
    }

    private Set<Long> snapshotOf(String bucket) {
        Set<String> members = redis.opsForZSet().range(bucket, 0, -1);
        if (members == null || members.isEmpty()) {
            return Set.of();
        }
        Set<Long> ids = new LinkedHashSet<>(members.size());
        for (String m : members) {
            try {
                ids.add(Long.parseLong(m));
            } catch (NumberFormatException e) {
                // 脏 member（人工塞的、或旧格式）：对账会把它当幽灵划掉
                log.warn("登记表里有非法 member，将按幽灵处理: bucket={}, member={}", bucket, m);
            }
        }
        return ids;
    }

    @Override
    public void addAll(Long userId, List<Long> taskIds) {
        addAllTo(accountKey(userId), taskIds);
    }

    @Override
    public void addAllByKey(Long apiKeyId, List<Long> taskIds) {
        addAllTo(keyKey(apiKeyId), taskIds);
    }

    private void addAllTo(String bucket, List<Long> taskIds) {
        if (taskIds == null || taskIds.isEmpty()) {
            return;
        }
        // score 用当下时间：补登记的任务从这一刻起重新计算老化。偏保守（晚一点才被淘汰），
        // 方向安全 —— 提前淘汰才是会造成超发的那个方向。
        double now = System.currentTimeMillis();
        Set<ZSetOperations.TypedTuple<String>> tuples = new LinkedHashSet<>();
        for (Long id : taskIds) {
            tuples.add(ZSetOperations.TypedTuple.of(String.valueOf(id), now));
        }
        redis.opsForZSet().add(bucket, tuples);
        redis.expire(bucket, java.time.Duration.ofSeconds(policy.agingWindowSeconds() * 2));
    }

    @Override
    public void removeAll(Long userId, List<Long> taskIds) {
        removeAllFrom(accountKey(userId), taskIds);
    }

    @Override
    public void removeAllByKey(Long apiKeyId, List<Long> taskIds) {
        removeAllFrom(keyKey(apiKeyId), taskIds);
    }

    private void removeAllFrom(String bucket, List<Long> taskIds) {
        if (taskIds == null || taskIds.isEmpty()) {
            return;
        }
        redis.opsForZSet().remove(bucket, taskIds.stream().map(String::valueOf).toArray());
    }

    @Override
    public Set<Long> trackedAccounts() {
        return scanIds(accountPrefix());
    }

    @Override
    public Set<Long> trackedKeys() {
        return scanIds(keyPrefix());
    }

    private Set<Long> scanIds(String prefix) {
        Set<Long> ids = new LinkedHashSet<>();
        // SCAN 而不是 KEYS：KEYS 会阻塞整个 Redis，在生产上就是一次事故
        try (Cursor<String> cursor = redis.scan(
                ScanOptions.scanOptions().match(prefix + "*").count(500).build())) {
            while (cursor.hasNext()) {
                String k = cursor.next();
                try {
                    ids.add(Long.parseLong(k.substring(prefix.length())));
                } catch (NumberFormatException e) {
                    log.warn("登记表里有非法 key，跳过: {}", k);
                }
            }
        }
        return ids;
    }

    private String base() {
        String prefix = properties.getRedisKeyPrefix();
        if (prefix == null || prefix.isBlank()) {
            prefix = "local:seedance:conc";
        }
        return prefix.replaceAll(":+$", "");
    }

    /** 账号桶前缀：企业买到的总量 */
    private String accountPrefix() {
        return base() + ":quota:user:";
    }

    /** key 桶前缀：企业内部分配给这把 key 的份额 */
    private String keyPrefix() {
        return base() + ":quota:key:";
    }

    private String accountKey(Long userId) {
        return accountPrefix() + userId;
    }

    private String keyKey(Long apiKeyId) {
        return keyPrefix() + apiKeyId;
    }

    private static long asLong(Object value) {
        return value instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(value));
    }
}
