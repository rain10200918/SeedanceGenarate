package org.example.seedancegenarate.service;

import org.example.seedancegenarate.config.RateLimitConfig;
import org.example.seedancegenarate.config.ConcurrencyProperties;
import org.example.seedancegenarate.entity.AppUser;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 在途并发上限的口径，以及老化窗口的启动校验。
 * <p>
 * 这一层不碰 Redis、不碰 DB —— 它只回答「这个账号的上限是多少」和「老化窗口多长」。
 * 口径散开必然漂移，而这条路径上漂移的代价是「客户以为买了 50 路，实际按 3 路拒他」。
 */
class ConcurrencyPolicyTest {

    private static final int TIMEOUT_MINUTES = 60;
    private static final int RETRY_MAX = 2;
    /** 60min × (2 次重试 + 首投) = 10800s。每次重试都会重置超时基准，所以是三段而不是一段 */
    private static final long MAX_LIFETIME_SECONDS = 10800L;

    private ConcurrencyPolicy policyWith(ConcurrencyProperties properties) {
        ConcurrencyPolicy policy = new ConcurrencyPolicy(properties, new RateLimitConfig());
        ReflectionTestUtils.setField(policy, "taskTimeoutMinutes", TIMEOUT_MINUTES);
        ReflectionTestUtils.setField(policy, "timeoutRetryMax", RETRY_MAX);
        return policy;
    }

    private ConcurrencyProperties props() {
        ConcurrencyProperties p = new ConcurrencyProperties();
        p.setTiers(new java.util.LinkedHashMap<>(Map.of("ENTERPRISE", 50, "STANDARD", 3)));
        return p;
    }

    private AppUser user(String tier, Integer override) {
        AppUser u = new AppUser();
        u.setId(7L);
        u.setAccountTier(tier);
        u.setConcurrencyOverride(override);
        return u;
    }

    @Test
    void accountWithNoTierAndNoOverrideIsUnlimited() {
        // 【测什么】库里三列全 NULL（= 迁移刚落地的状态）时判定为「不限」
        // 【怎么算红】给个非空默认上限 —— 那是对全体存量个人用户的一次静默降级，
        //          他们没有合同也没有预期管理，第一个发现的会是客服；
        //          而且「不限」还决定了提交路径整个跳过 Redis，退化成改动前的行为
        ConcurrencyLimit limit = policyWith(props()).resolve(user(null, null));

        assertTrue(limit.unlimited(), "无档位无 override 必须是不限，实际=" + limit);
    }

    @Test
    void overrideBeatsTheTier() {
        // 【测什么】单客户特谈的 override 优先于档位表
        // 【怎么算红】先查档位、override 只在档位缺失时兜底 —— 那么「这家客户单独谈了 200 路」
        //          就永远生效不了，销售承诺和系统行为对不上
        ConcurrencyLimit limit = policyWith(props()).resolve(user("ENTERPRISE", 200));

        assertFalse(limit.unlimited());
        assertEquals(200, limit.max(), "override 必须压过档位");
    }

    @Test
    void overrideOfZeroMeansForbiddenNotUnlimited() {
        // 【测什么】override=0 是「禁止提交」（管理员封禁用），不是「不限」
        // 【怎么算红】用 `override != null && override > 0` 这类判断 —— 0 会掉进不限分支，
        //          于是「封禁」这个动作把客户放成了无限额，方向完全反了
        ConcurrencyLimit limit = policyWith(props()).resolve(user(null, 0));

        assertFalse(limit.unlimited(), "0 不是不限，实际=" + limit);
        assertEquals(0, limit.max());
    }

    @Test
    void unknownTierFallsBackToUnlimitedNotToZero() {
        // 【测什么】库里写了配置里没有的档位名时，方向是「放行」而不是「拒死」
        // 【怎么算红】找不到就当 0 或抛异常 —— 运维拼错一个档位名（"ENTERPRISE " 带空格、
        //          或改配置时删错一行）就会把这家企业的接入整个打死，而且报错发生在提交路径上
        ConcurrencyLimit limit = policyWith(props()).resolve(user("GOLD", null));

        assertTrue(limit.unlimited(), "未知档位必须按不限处理，实际=" + limit);
    }

    @Test
    void masterSwitchOffMakesEveryoneUnlimited() {
        // 【测什么】总开关关掉时，连有档位的企业也判为不限
        // 【怎么算红】开关只挡住拒绝、不挡住判定 —— 那么「关掉开关」并不能真的回到改动前，
        //          出事时最想用的那个逃生口是坏的
        ConcurrencyProperties p = props();
        p.setEnabled(false);

        assertTrue(policyWith(p).resolve(user("ENTERPRISE", null)).unlimited());
    }

    private org.example.seedancegenarate.entity.ApiKey key(Integer maxConcurrency) {
        org.example.seedancegenarate.entity.ApiKey k = new org.example.seedancegenarate.entity.ApiKey();
        k.setId(1L);
        k.setMaxConcurrency(maxConcurrency);
        return k;
    }

    @Test
    void keyShareCanOnlyTightenNeverWiden() {
        // 【测什么】key 份额填得比账号总量还大时，按账号总量算
        // 【怎么算红】直接采用 key 上填的数 —— 企业自己就能建一把 key 填个大数来扩容，
        //          等于自助绕过它买到的量。这和 D-030 里「按 key 分桶」是同一个洞换了个入口，
        //          而正是「只能收紧」这条让这个开关能安全地放给用户自己设
        ConcurrencyLimit limit = policyWith(props()).resolve(user(null, 10), key(999));

        assertEquals(10, limit.keyMax(), "key 份额不能超过账号总量");
        assertEquals(10, limit.accountMax());
    }

    @Test
    void keyShareTightensWithinTheAccountTotal() {
        // 【测什么】正常的分配（份额 < 总量）原样生效
        // 【怎么算红】无脑取 min 之外还动了值 —— 企业分配的数字对不上，
        //          「生产 38 / 测试 2」变成别的数，而这是他们内部沟通的依据
        ConcurrencyLimit limit = policyWith(props()).resolve(user(null, 50), key(2));

        assertEquals(2, limit.keyMax());
        assertEquals(50, limit.accountMax());
    }

    @Test
    void noKeyShareMeansSharingTheAccountTotal() {
        // 【测什么】key 没设份额时，keyMax 为 null —— 也就是「和其他 key 共用账号总量」
        // 【怎么算红】给个默认份额 —— 个人用户的 50 把 key 会各自拿到一份，
        //          账号总量形同虚设，正是「多把 key 不该放大容量」要挡的那件事
        ConcurrencyLimit limit = policyWith(props()).resolve(user(null, 50), key(null));

        assertNull(limit.keyMax(), "没分配份额就该是 null（共用总量）");
        assertEquals(-1, limit.keyCapForScript(), "-1 = 脚本里整个跳过 key 桶");
    }

    @Test
    void webAndCanvasHaveNoKeyShareAtAll() {
        // 【测什么】没有 apiKey 的路径（网页、画布）只受账号总量管
        // 【怎么算红】给这些路径也套上某把 key 的份额 —— 用户在网页上点一下，
        //          却被一个他根本没用到的密钥的份额拦住，报错完全无法理解
        ConcurrencyLimit limit = policyWith(props()).resolve(user(null, 50), null);

        assertNull(limit.keyMax());
    }

    @Test
    void agingWindowIsDerivedFromTaskTimeoutTimesRetries() {
        // 【测什么】老化窗口 = 任务最长合法寿命 × 安全系数，且寿命算的是「首投 + 重试」多段
        // 【怎么算红】只按一段超时算（漏掉 ×(retryMax+1)）—— 窗口变成 1/3，
        //          一个重试过的长任务会在**还在跑的时候**被淘汰、槽位提前释放（超发），
        //          而且不报错、不告警，只在高峰期发生
        ConcurrencyPolicy policy = policyWith(props());

        assertEquals(MAX_LIFETIME_SECONDS, policy.maxTaskLifetimeSeconds());
        assertEquals(MAX_LIFETIME_SECONDS * 2, policy.agingWindowSeconds(), "默认安全系数 2");
    }

    @Test
    void agingOverrideBelowTaskLifetimeFailsAtStartup() {
        // 【测什么】人工把老化窗口配得比任务寿命还短时，启动直接失败
        // 【怎么算红】只在运行时用 —— 这个错不会报错、不会告警，只会在某个高峰期让一批
        //          还在跑的任务悄悄丢掉槽位；等到发现时已经超发了很久
        ConcurrencyProperties p = props();
        p.setAgingWindowSecondsOverride(MAX_LIFETIME_SECONDS - 1);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> policyWith(p).validateAndReport());
        assertTrue(e.getMessage().contains("小于任务合法寿命"), "报错要说清原因，实际=" + e.getMessage());
    }

    @Test
    void agingOverrideAtOrAboveTaskLifetimeIsAccepted() {
        // 【测什么】刚好等于寿命的边界值放行（边界不能一刀切掉合法配置）
        // 【怎么算红】用 <= 判定 —— 运维按文档算出来的那个精确值反而启动不了，
        //          于是他们会随手乘个 10，这道校验就失去意义了
        ConcurrencyProperties p = props();
        p.setAgingWindowSecondsOverride(MAX_LIFETIME_SECONDS);

        ConcurrencyPolicy policy = policyWith(p);
        policy.validateAndReport();

        assertEquals(MAX_LIFETIME_SECONDS, policy.agingWindowSeconds(), "人工覆盖要真的生效");
    }

    @Test
    void brokenTimeoutConfigFailsAtStartup() {
        // 【测什么】超时配成 0/负数时启动失败（老化窗口会被推导成 0 = 一切立刻过期）
        // 【怎么算红】不校验 —— 窗口 0 意味着每次 acquire 前都把所有登记淘汰光，
        //          限额永远数到 0、永远放行，整个功能静默失效且看不出来
        ConcurrencyPolicy policy = policyWith(props());
        ReflectionTestUtils.setField(policy, "taskTimeoutMinutes", 0);

        assertThrows(IllegalStateException.class, policy::validateAndReport);
    }

    @Test
    void everyDerivedBucketCanActuallyFillItsSeats() {
        // 【测什么】对一串席位数，「推导出的桶撑得住的在途数 >= 席位数」恒成立
        // 【怎么算红】ceil 写成 floor、或者除错了方向 —— 只差一点点的话单点测试很容易蒙对，
        //          而症状是「卖了 50 席实际只到 49」这种没人会去查、只会慢慢变成客诉的偏差
        ConcurrencyPolicy policy = policyWith(props());

        for (int seats : new int[]{1, 2, 5, 9, 10, 11, 25, 50, 51, 100, 200, 3000}) {
            int reachable = policy.reachableInFlight(policy.apiBucketFor(seats));
            assertTrue(reachable >= seats,
                    "席位=" + seats + " 时接口只撑得住 " + reachable + "，卖出去的量到不了");
        }
    }

    @Test
    void derivedBucketNeverGoesBelowTheDefaultOne() {
        // 【测什么】席位很少时推导结果不低于默认桶（两处 max 都在）
        // 【怎么算红】去掉 max —— 1 席的账号会被推成 capacity=1/refill=1，比今天的 10/5 还紧。
        //          这一刀的安全性建立在「最坏情况等于什么都没做」上，少了 max 就变成
        //          「顺手把小客户弄严了」，而且没有任何测试会自然覆盖到这一点
        RateLimitConfig.Bucket defaults = new RateLimitConfig().getApiKey();
        ConcurrencyPolicy policy = policyWith(props());

        for (int seats : new int[]{0, 1, 2, 5, 10, 24}) {
            RateLimitConfig.Bucket b = policy.apiBucketFor(seats);
            assertTrue(b.getCapacity() >= defaults.getCapacity(),
                    "席位=" + seats + " 推出的突发上限 " + b.getCapacity() + " 比默认还小");
            assertTrue(b.getRefillTokens() >= defaults.getRefillTokens(),
                    "席位=" + seats + " 推出的补充速率 " + b.getRefillTokens() + " 比默认还小");
        }
    }

    @Test
    void switchingOffTheDerivationReturnsTheDefaultBucketUntouched() {
        // 【测什么】关掉自动放宽后拿到的就是默认桶那个对象的参数，一个字不改
        // 【怎么算红】关掉后还是返回推导值 —— 那这个逃生口是坏的，
        //          而它存在的全部意义就是「出事时能一键退回改动前」
        ConcurrencyProperties p = props();
        p.setDeriveApiRateLimit(false);
        RateLimitConfig.Bucket defaults = new RateLimitConfig().getApiKey();

        RateLimitConfig.Bucket b = policyWith(p).apiBucketFor(500);

        assertEquals(defaults.getCapacity(), b.getCapacity());
        assertEquals(defaults.getRefillTokens(), b.getRefillTokens());
    }

    @Test
    void accountMaxIgnoresTheKeyShareEntirely() {
        // 【测什么】accountMax() 只看管理员字段 —— 这是两档令牌桶的判别条件
        // 【怎么算红】让它也读 api_key.max_concurrency（或者干脆改用 resolve().unlimited()）——
        //          那一列是用户自助能改的（PATCH /api/api-keys/{id}/share），
        //          于是任何个人用户给自己的 key 设个份额就把自己升级成了「有席位的账号」，
        //          从此走松桶，而账号侧没有任何总量约束这些份额的和。D-030 那个洞的第三次变形
        ConcurrencyPolicy policy = policyWith(props());

        assertNull(policy.accountMax(user(null, null)),
                "没有档位也没有 override = 没买额度，不管 key 上写了什么");
        assertEquals(10, policy.accountMax(user(null, 10)));
    }

    @Test
    void masterSwitchOffAlsoClearsTheAccountMax() {
        // 【测什么】总开关关掉时 accountMax() 也回 null
        // 【怎么算红】只有 resolve() 认这个开关 —— 那么关掉功能之后，令牌桶那一档还在
        //          按席位放宽，「彻底退回改动前」这个逃生口又是半截的
        ConcurrencyProperties p = props();
        p.setEnabled(false);

        assertNull(policyWith(p).accountMax(user("ENTERPRISE", null)));
    }

    @Test
    void zeroAvgTaskMinutesFailsAtStartup() {
        // 【测什么】任务平均分钟数配成 0 时启动失败
        // 【怎么算红】不校验 —— 它是「席位数 ÷ 本值」的除数，0 会让推导出 Infinity 再转成
        //          Integer.MIN_VALUE 之类的垃圾值，而崩溃现场在 API 限流的热路径上，离配置很远
        ConcurrencyProperties p = props();
        p.setAvgTaskMinutes(0);

        assertThrows(IllegalStateException.class, () -> policyWith(p).validateAndReport());
    }

    @Test
    void negativeTierValueFailsAtStartup() {
        // 【测什么】档位数值配成负数时启动失败
        // 【怎么算红】不校验 —— 负数会让「已用 >= 上限」恒成立，这个档位的所有客户
        //          一个请求都提交不了，而错误现场在提交路径上、离配置很远
        ConcurrencyProperties p = props();
        p.getTiers().put("BROKEN", -1);

        assertThrows(IllegalStateException.class, () -> policyWith(p).validateAndReport());
    }
}
