package org.example.seedancegenarate.interceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.seedancegenarate.config.RateLimitConfig;
import org.example.seedancegenarate.entity.ApiKey;
import org.example.seedancegenarate.service.RateLimitResult;
import org.example.seedancegenarate.service.TokenBucketRateLimitService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 配额属于<b>账号</b>，不属于 key。
 * <p>
 * 令牌桶原本按 {@code api-key:{keyId}} 分桶。在「只有管理员能发 key」的世界里这没问题；
 * 一旦下放自助创建，<b>建 N 把 key 就是 N 个桶 = N 倍配额</b>——自助创建 key
 * 等于自助绕过限流。key 只是凭证，账号发几把是它自己的组织方式，
 * 不该改变它买到的量。
 */
class ApiKeyQuotaIsPerAccountTest {

    private static final Long OWNER = 7L;

    private TokenBucketRateLimitService rateLimitService;
    private ApiKeyRateLimitInterceptor interceptor;
    private org.example.seedancegenarate.mapper.AppUserMapper appUserMapper;
    private final List<String> bucketKeys = new ArrayList<>();
    private final List<RateLimitConfig.Bucket> buckets = new ArrayList<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
                org.example.seedancegenarate.entity.AppUser.class);

        rateLimitService = mock(TokenBucketRateLimitService.class);
        bucketKeys.clear();
        buckets.clear();
        when(rateLimitService.tryAcquire(anyString(), any())).thenAnswer(inv -> {
            bucketKeys.add(inv.getArgument(0));
            buckets.add(inv.getArgument(1));
            return RateLimitResult.permitted();
        });

        appUserMapper = mock(org.example.seedancegenarate.mapper.AppUserMapper.class);
        when(appUserMapper.selectList(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class)))
                .thenReturn(List.of());

        org.example.seedancegenarate.config.ConcurrencyProperties props =
                new org.example.seedancegenarate.config.ConcurrencyProperties();
        org.example.seedancegenarate.service.ConcurrencyPolicy policy =
                new org.example.seedancegenarate.service.ConcurrencyPolicy(props, new RateLimitConfig());
        org.springframework.test.util.ReflectionTestUtils.setField(policy, "taskTimeoutMinutes", 60);
        org.springframework.test.util.ReflectionTestUtils.setField(policy, "timeoutRetryMax", 2);

        interceptor = new ApiKeyRateLimitInterceptor(
                rateLimitService, new RateLimitConfig(),
                new org.example.seedancegenarate.service.MeteredAccountRegistry(appUserMapper, policy),
                policy, new ObjectMapper());
    }

    /** 让这个账号在「买了席位」的名单里，席位数 = seats */
    @SuppressWarnings("unchecked")
    private void givenSeats(Long userId, int seats) {
        org.example.seedancegenarate.entity.AppUser u = new org.example.seedancegenarate.entity.AppUser();
        u.setId(userId);
        u.setConcurrencyOverride(seats);
        when(appUserMapper.selectList(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class)))
                .thenReturn(List.of(u));
    }

    private ApiKey key(Long id, Long userId) {
        ApiKey k = new ApiKey();
        k.setId(id);
        k.setUserId(userId);
        return k;
    }

    private void submitWith(ApiKey apiKey) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/videos");
        request.setAttribute("api_key", apiKey);
        interceptor.preHandle(request, new MockHttpServletResponse(), new Object());
    }

    @Test
    void twoKeysOfTheSameAccountShareOneBucket() throws Exception {
        // 【测什么】同一账号的两把 key 命中**同一个**桶
        // 【怎么算红】按 key id 分桶（改动前的写法）—— 用户自助建 50 把 key 就是 50 倍配额，
        //          限流形同虚设，而这个洞恰恰是「下放创建权」这个功能自己打开的
        submitWith(key(1L, OWNER));
        submitWith(key(2L, OWNER));

        assertEquals(1, bucketKeys.stream().distinct().count(),
                "同账号的多把 key 必须共用一个桶，实际用了: " + bucketKeys);
        assertTrue(bucketKeys.get(0).contains(String.valueOf(OWNER)),
                "分桶键要按账号，实际=" + bucketKeys.get(0));
    }

    @Test
    void differentAccountsStillGetSeparateBuckets() throws Exception {
        // 【测什么】不同账号仍然各自独立
        // 【怎么算红】分桶键写死成常量 —— 全平台共用一个桶，一个客户打满，所有客户一起 429
        submitWith(key(1L, OWNER));
        submitWith(key(2L, 99L));

        assertEquals(2, bucketKeys.stream().distinct().count(),
                "不同账号必须分开，实际=" + bucketKeys);
    }

    @Test
    void bucketKeyDoesNotContainTheKeyId() throws Exception {
        // 【测什么】分桶键里不含 key id —— 这是「配额属于账号」这条语义的结构性证据
        // 【怎么算红】把 keyId 拼回分桶键（哪怕只是加个后缀）—— 多建 key 又能放大配额了
        submitWith(key(123456L, OWNER));

        assertTrue(bucketKeys.get(0).contains("123456") == false,
                "分桶键不该含 key id，实际=" + bucketKeys.get(0));
    }

    // ---------- 两档桶：买了席位的账号走推导出来的松桶 ----------

    @Test
    void accountWithoutSeatsKeepsTheDefaultBucketExactly() throws Exception {
        // 【测什么】没买席位的账号（= 全体个人用户）拿到的桶参数与改动前**逐字相同**
        // 【怎么算红】把松桶无差别发给所有人 —— 个人防刷是靠这个桶撑着的（并发额度对他们
        //          整个不生效，一次 Redis 都不碰），放宽它等于把 C 端防刷直接拆了
        submitWith(key(1L, OWNER));

        RateLimitConfig.Bucket used = buckets.get(0);
        RateLimitConfig.Bucket expected = new RateLimitConfig().getApiKey();
        assertEquals(expected.getCapacity(), used.getCapacity(), "个人用户的突发上限不该变");
        assertEquals(expected.getRefillTokens(), used.getRefillTokens(), "个人用户的补充速率不该变");
    }

    @Test
    void accountWithSeatsGetsABucketThatCanActuallyFillThem() throws Exception {
        // 【测什么】50 席的账号拿到 capacity=50 / 每分钟 10 —— 恰好够把 50 席持续填满
        //          （每分钟 10 次 × 任务平均 5 分钟 = 稳态 50 个在途）
        // 【怎么算红】继续用默认桶 —— 5 次/分钟 × 5 分钟 = 只撑得住 25 个，
        //          于是我们卖出去的 50 席有一半永远到不了，客户按合同来投诉时我们还查不出原因
        givenSeats(OWNER, 50);

        submitWith(key(1L, OWNER));

        RateLimitConfig.Bucket used = buckets.get(0);
        assertEquals(50, used.getCapacity(), "突发上限要够一次填满席位");
        assertEquals(10, used.getRefillTokens(), "每分钟要放够 席位数÷任务分钟数 次");
    }

    @Test
    void derivedBucketNeverTightensBelowTheDefault() throws Exception {
        // 【测什么】席位很少（1 席）时不会推出一个比默认还紧的桶
        // 【怎么算红】去掉 apiBucketFor 里的两处 max() —— 1 席会被推成 capacity=1，
        //          比今天的 10 还紧。这一刀本该「最坏情况等于什么都没做」，
        //          少了 max 就变成「顺手把小客户弄坏了」，而这类回归最难在测试里想到
        givenSeats(OWNER, 1);

        submitWith(key(1L, OWNER));

        RateLimitConfig.Bucket used = buckets.get(0);
        RateLimitConfig.Bucket expected = new RateLimitConfig().getApiKey();
        assertEquals(expected.getCapacity(), used.getCapacity());
        assertEquals(expected.getRefillTokens(), used.getRefillTokens());
    }

    @Test
    void aSelfServiceKeyShareCannotPromoteAnAccountIntoTheLooseBucket() throws Exception {
        // 【测什么】账号没买席位、但这把 key 自己设了份额时，仍然走**默认桶**
        // 【怎么算红】判别条件用 resolve(user, apiKey).unlimited() 而不是只看管理员字段 ——
        //          api_key.max_concurrency 是用户自助能改的（PATCH /api/api-keys/{id}/share），
        //          那样任何个人用户给自己的 key 随手设个份额就把自己送进了松桶，
        //          而账号侧没有任何总量去约束这些份额的和。D-030 那个洞的第三次变形
        // 份额取一个**大到能看出区别**的值：小份额经 apiBucketFor 的 max() 兜底后
        // 恰好等于默认桶，用它做断言的话，即使判别条件真的被改成读 key 份额，
        // 两边数字也一样 —— 测试会绿着空过（这条最初就是这么写错的）
        ApiKey selfConfigured = key(1L, OWNER);
        selfConfigured.setMaxConcurrency(500);

        submitWith(selfConfigured);

        RateLimitConfig.Bucket used = buckets.get(0);
        RateLimitConfig.Bucket expected = new RateLimitConfig().getApiKey();
        assertEquals(expected.getCapacity(), used.getCapacity(),
                "用户自助设的 key 份额不许改变他走哪档桶");
        assertEquals(expected.getRefillTokens(), used.getRefillTokens());
    }
}
