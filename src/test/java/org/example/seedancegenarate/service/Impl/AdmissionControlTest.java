package org.example.seedancegenarate.service.Impl;

import org.example.seedancegenarate.config.RateLimitConfig;
import org.example.seedancegenarate.config.ConcurrencyProperties;
import org.example.seedancegenarate.service.AdmissionResult;
import org.example.seedancegenarate.service.ConcurrencyLimit;
import org.example.seedancegenarate.service.ConcurrencyPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 占位/还位的 Java 侧行为：跳过、降级、以及「还位不判限额」。
 * <p>
 * Lua 脚本本身（原子性、老化、重入）要真 Redis 才测得了，不在这一层。
 * 这里挡的是三个<b>纯 Java 判断错了就出事</b>的地方。
 */
class AdmissionControlTest {

    private static final Long OWNER = 7L;
    private static final Long TASK = 123L;

    private StringRedisTemplate redis;
    private AdmissionControlImpl admission;
    private ConcurrencyProperties properties;

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        properties = new ConcurrencyProperties();
        ConcurrencyPolicy policy = new ConcurrencyPolicy(properties, new RateLimitConfig());
        ReflectionTestUtils.setField(policy, "taskTimeoutMinutes", 60);
        ReflectionTestUtils.setField(policy, "timeoutRetryMax", 2);
        admission = new AdmissionControlImpl(redis, properties, policy);
    }

    @Test
    void unlimitedAccountsNeverTouchRedis() {
        // 【测什么】不限额的账号（= 全部个人用户）在提交路径上**一次 Redis 调用都不发**
        // 【怎么算红】先无脑 ZADD 再判断限额 —— 全站每一次提交都多一次 Redis 往返，
        //          而且个人用户会凭空在 Redis 里堆出成千上万个 ZSET，
        //          对账要扫的账号数从「几个企业」变成「全站用户」，这条路就不可能保持 2 秒一轮
        AdmissionResult result = admission.acquire(OWNER, TASK, null, ConcurrencyLimit.UNLIMITED);

        assertFalse(result.tracked(), "不限额不该进登记表");
        assertTrue(result.admitted());
        assertEquals(0, mockingDetails(redis).getInvocations().size(),
                "不限额时必须一次 Redis 都不碰，实际调用: " + mockingDetails(redis).getInvocations());
    }

    @Test
    void redisFailureLetsTheRequestThroughInsteadOfBlockingIt() {
        // 【测什么】Redis 抛异常时放行，而不是拒绝
        // 【怎么算红】改成 fail-closed —— 这一层是商业约定不是安全带（真正的止损是钱包，
        //          走 MySQL 事务不受影响），而 Redis 全挂时上游令牌桶已经 fail-closed 拒完了；
        //          在这儿再拒一次，只会在「Redis 半死不活」时把还能服务的客户也一起打死
        when(redis.execute(any(RedisScript.class), any(), any(Object[].class)))
                .thenThrow(new RuntimeException("READONLY You can't write against a replica"));

        AdmissionResult result = admission.acquire(OWNER, TASK, null, ConcurrencyLimit.of(50));

        assertTrue(result.admitted(), "Redis 故障必须放行");
        assertFalse(result.tracked(), "没登记上，要让对账知道这条得补");
    }

    @Test
    void overLimitIsRejectedWhenNotInShadowMode() {
        // 【测什么】非影子模式下，脚本说「拒」就真的拒
        // 【怎么算红】把脚本返回值读错位（admitted 和 wouldReject 取反或错位）——
        //          限额要么永远不生效、要么把所有人都拒了，两种都极难从日志上看出来
        properties.setShadow(false);
        when(redis.execute(any(RedisScript.class), any(), any(Object[].class)))
                .thenReturn(List.of(0L, 1L, 50L, 0L, 1L));

        AdmissionResult result = admission.acquire(OWNER, TASK, null, ConcurrencyLimit.of(50));

        assertFalse(result.admitted(), "超限必须拒");
        assertTrue(result.wouldReject());
        assertEquals(50L, result.accountCurrent());
        assertEquals(50, result.accountLimit());
    }

    @Test
    void shadowModeAdmitsButStillReportsThatItWouldHaveRejected() {
        // 【测什么】影子模式下放行，但 wouldReject 说实话
        // 【怎么算红】影子模式直接 return 不走脚本 —— 那么登记表是空的、计数失真，
        //          影子期看到的在途分布全是错的，而看清真实分布正是跑影子的唯一目的
        properties.setShadow(true);
        when(redis.execute(any(RedisScript.class), any(), any(Object[].class)))
                .thenReturn(List.of(1L, 1L, 51L, 0L, 1L));

        AdmissionResult result = admission.acquire(OWNER, TASK, null, ConcurrencyLimit.of(50));

        assertTrue(result.admitted(), "影子模式不拒绝");
        assertTrue(result.wouldReject(), "但要如实报告『本来会拒』");
    }

    @Test
    @SuppressWarnings("unchecked")
    void releaseDoesNotAskWhetherTheAccountHasALimit() {
        // 【测什么】还位无条件尝试 ZREM，不去查这个账号有没有限额
        // 【怎么算红】加一个「不限额就跳过」的判断 —— 档位是管理员可以在任务**在途期间**加上的，
        //          那时按「提交时不限额」跳过释放，会留下一个只能等好几小时老化的幽灵。
        //          ZREM 不存在的 key 是 O(1)，不值得为省这一下引入这个漏洞
        ZSetOperations<String, String> zset = mock(ZSetOperations.class);
        when(redis.opsForZSet()).thenReturn(zset);

        admission.releaseQuietly(OWNER, TASK, null);

        verify(zset).remove(anyString(), any(Object[].class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void releaseSwallowsRedisFailures() {
        // 【测什么】还位失败只记日志，不往上抛
        // 【怎么算红】让异常冒出去 —— 它的调用点在终态收尾里，紧挨着已经结算完的成功任务；
        //          抛出去会让一笔钱已经扣好的任务看起来「失败」，而槽位本来就有对账兜底
        ZSetOperations<String, String> zset = mock(ZSetOperations.class);
        when(redis.opsForZSet()).thenReturn(zset);
        when(zset.remove(anyString(), any(Object[].class)))
                .thenThrow(new RuntimeException("connection reset"));

        admission.releaseQuietly(OWNER, TASK, null); // 不抛即通过
    }

    @Test
    void masterSwitchOffAlsoSkipsRelease() {
        // 【测什么】总开关关掉后连还位都不发 Redis
        // 【怎么算红】开关只挡 acquire —— 那么「关掉开关」之后系统还在往 Redis 打，
        //          出事时想彻底摘掉这条链路的那个逃生口是半截的
        properties.setEnabled(false);

        admission.releaseQuietly(OWNER, TASK, null);

        verify(redis, never()).opsForZSet();
    }
}
