package org.example.seedancegenarate.task;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.example.seedancegenarate.config.RateLimitConfig;
import org.example.seedancegenarate.config.ConcurrencyProperties;
import org.example.seedancegenarate.config.DistributedLockProperties;
import org.example.seedancegenarate.entity.AppUser;
import org.example.seedancegenarate.entity.VideoTask;
import org.example.seedancegenarate.mapper.AppUserMapper;
import org.example.seedancegenarate.mapper.VideoTaskMapper;
import org.example.seedancegenarate.service.AdmissionControl;
import org.example.seedancegenarate.service.ConcurrencyPolicy;
import org.example.seedancegenarate.service.DistributedLock;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 对账的双向修复，以及两条「错了不报错」的规则。
 * <p>
 * 这个类里的每一条都对应一个<b>不会有任何报错、只会静默超发或少发</b>的写法。
 * 它们不像空指针那样自己暴露，只能靠测试挡住。
 */
class ConcurrencyReconcileTaskTest {

    private static final Long OWNER = 7L;

    @BeforeAll
    static void initTableInfo() {
        for (Class<?> entity : List.of(AppUser.class, VideoTask.class,
                org.example.seedancegenarate.entity.ApiKey.class)) {
            com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                    new org.apache.ibatis.builder.MapperBuilderAssistant(
                            new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
                    entity);
        }
    }

    private AdmissionControl admission;
    private AppUserMapper appUserMapper;
    private VideoTaskMapper videoTaskMapper;
    private org.example.seedancegenarate.mapper.ApiKeyMapper apiKeyMapper;
    private org.example.seedancegenarate.service.MeteredAccountRegistry registry;
    private ConcurrencyReconcileTask task;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        admission = mock(AdmissionControl.class);
        appUserMapper = mock(AppUserMapper.class);
        videoTaskMapper = mock(VideoTaskMapper.class);

        ConcurrencyProperties properties = new ConcurrencyProperties();
        properties.setTiers(new java.util.LinkedHashMap<>(java.util.Map.of("ENTERPRISE", 50)));
        ConcurrencyPolicy policy = new ConcurrencyPolicy(properties, new RateLimitConfig());
        ReflectionTestUtils.setField(policy, "taskTimeoutMinutes", 60);
        ReflectionTestUtils.setField(policy, "timeoutRetryMax", 2);

        DistributedLockProperties lockProps = new DistributedLockProperties();
        lockProps.setEnabled(false); // 单实例路径，直接执行

        apiKeyMapper = mock(org.example.seedancegenarate.mapper.ApiKeyMapper.class);
        when(apiKeyMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        // 名单查询已经搬进 MeteredAccountRegistry，这里用**真的** registry 配 mock 的 mapper，
        // 让「WHERE tier IS NOT NULL OR override IS NOT NULL」这条查询仍然被这些用例覆盖
        registry = new org.example.seedancegenarate.service.MeteredAccountRegistry(appUserMapper, policy);
        task = new ConcurrencyReconcileTask(admission, properties, registry,
                apiKeyMapper, videoTaskMapper,
                mock(DistributedLock.class), lockProps);

        // 默认：一个企业账号，Redis 里没别的账号
        when(appUserMapper.selectList(any(Wrapper.class))).thenReturn(List.of(enterprise(OWNER)));
        when(admission.trackedAccounts()).thenReturn(Set.of());
        when(admission.trackedKeys()).thenReturn(Set.of());
        when(admission.snapshot(any())).thenReturn(Set.of());
        when(videoTaskMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
    }

    private AppUser enterprise(Long id) {
        AppUser u = new AppUser();
        u.setId(id);
        u.setAccountTier("ENTERPRISE");
        return u;
    }

    /** 落库 secondsAgo 秒之前的在途任务 */
    private VideoTask inFlight(Long id, long secondsAgo) {
        VideoTask t = new VideoTask();
        t.setId(id);
        t.setUserId(OWNER);
        t.setStatus("PROCESSING");
        t.setCreateTime(LocalDateTime.now().minusSeconds(secondsAgo));
        return t;
    }

    @SuppressWarnings("unchecked")
    private List<Long> capturedRemove() {
        ArgumentCaptor<List<Long>> c = ArgumentCaptor.forClass(List.class);
        verify(admission).removeAll(eq(OWNER), c.capture());
        return c.getValue();
    }

    @SuppressWarnings("unchecked")
    private List<Long> capturedAdd() {
        ArgumentCaptor<List<Long>> c = ArgumentCaptor.forClass(List.class);
        verify(admission).addAll(eq(OWNER), c.capture());
        return c.getValue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void readsRedisBeforeReadingTheDatabase() {
        // 【测什么】快照顺序：先读 Redis，再读 DB
        // 【怎么算红】把两次读调换顺序 —— 读完 DB（10 条）之后、读 Redis 之前又 admit 进来一条，
        //          Redis 里 11 条，差集判定新来的那条是幽灵并划掉；可它真的在跑，
        //          槽位白占了 = 超发。这个错不会抛异常、不会打日志，只在有并发时发生
        task.reconcileLocked();

        InOrder order = inOrder(admission, videoTaskMapper);
        order.verify(admission).snapshot(OWNER);
        order.verify(videoTaskMapper).selectList(any(Wrapper.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void removesGhostsThatAreNoLongerRunning() {
        // 【测什么】登记表有、DB 已终态 → 划掉（少发方向的修复）
        // 【怎么算红】不做这一侧 —— 每一次丢失的 ZREM 都会永久占着一路，
        //          企业的可用并发只减不增，最后「付了钱一条都提交不了」
        when(admission.snapshot(OWNER)).thenReturn(new java.util.LinkedHashSet<>(List.of(1L, 2L)));
        when(videoTaskMapper.selectList(any(Wrapper.class))).thenReturn(List.of(inFlight(1L, 60)));

        task.reconcileLocked();

        assertEquals(List.of(2L), capturedRemove(), "只该划掉不在跑的那条");
    }

    @Test
    @SuppressWarnings("unchecked")
    void addsBackSlotsThatAreRunningButMissingFromRedis() {
        // 【测什么】DB 在跑、登记表没有（且已过成熟期）→ 补登记（超发方向的修复）
        // 【怎么算红】不做这一侧 —— Redis failover 丢掉 ZADD 之后没有任何机制能发现，
        //          「记录待释放事件」那类方案结构上也修不了：它只记释放，对占位一无所知
        when(videoTaskMapper.selectList(any(Wrapper.class))).thenReturn(List.of(inFlight(5L, 60)));

        task.reconcileLocked();

        assertEquals(List.of(5L), capturedAdd());
    }

    @Test
    @SuppressWarnings("unchecked")
    void doesNotAddBackTasksThatWereJustCreated() {
        // 【测什么】刚落库还没来得及占位的任务，不给它补登记
        // 【怎么算红】补登记不加成熟期过滤 —— save(task) 在 acquire() 之前，
        //          对账撞进这个窗口就替它把位占上了，等于绕过门口数人头，限额白设
        when(videoTaskMapper.selectList(any(Wrapper.class))).thenReturn(List.of(inFlight(9L, 1)));

        task.reconcileLocked();

        verify(admission, never()).addAll(any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void freshlyAdmittedTasksAreNotMistakenForGhosts() {
        // 【测什么】刚 admit 的任务（DB 很新 + 已在登记表）不能被划掉
        // 【怎么算红】把成熟期过滤也用在「划掉」那一侧 —— 新任务被排除出「实际在跑」，
        //          于是**每一条新登记都会被立刻划掉**，限额恒等于 0 个在途，整个功能静默失效。
        //          这是上一条的镜像，两条必须同时在
        when(admission.snapshot(OWNER)).thenReturn(new java.util.LinkedHashSet<>(List.of(9L)));
        when(videoTaskMapper.selectList(any(Wrapper.class))).thenReturn(List.of(inFlight(9L, 1)));

        task.reconcileLocked();

        verify(admission, never()).removeAll(any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void scansRedisForAccountsThatHaveNoRowsInTheDatabase() {
        // 【测什么】DB 里一条在跑的都没有、Redis 还挂着幽灵的账号，也要被扫到
        // 【怎么算红】只按「配了限额的账号」或只按 DB 的 GROUP BY 取账号 ——
        //          这类账号不出现在任何结果集里，差集永远算不到它，
        //          幽灵只能等好几个小时的老化，而它期间一直占着这家客户的额度
        Long onlyInRedis = 99L;
        when(admission.trackedAccounts()).thenReturn(Set.of(onlyInRedis));
        when(admission.snapshot(onlyInRedis)).thenReturn(new java.util.LinkedHashSet<>(List.of(3L)));

        task.reconcileLocked();

        ArgumentCaptor<List<Long>> c = ArgumentCaptor.forClass(List.class);
        verify(admission).removeAll(eq(onlyInRedis), c.capture());
        assertEquals(List.of(3L), c.getValue());
    }

    @Test
    @SuppressWarnings("unchecked")
    void unlimitedAccountsAreNotReconciledAtAll() {
        // 【测什么】档位解析下来是「不限」的账号不进对账
        // 【怎么算红】只看「account_tier 非空」就纳入 —— 档位名拼错时该账号本来按不限放行、
        //          Redis 里一条登记都没有，对账却会把它全部在途任务补登记进去，
        //          凭空给个人用户造出一套他们从来没被限过的额度
        AppUser bogusTier = new AppUser();
        bogusTier.setId(OWNER);
        bogusTier.setAccountTier("NOT_IN_CONFIG");
        when(appUserMapper.selectList(any(Wrapper.class))).thenReturn(List.of(bogusTier));

        task.reconcileLocked();

        verify(admission, never()).snapshot(any());
        verify(videoTaskMapper, never()).selectList(any(Wrapper.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void doesNotFullScanAppUserOnEveryRound() {
        // 【测什么】「谁有限额」的名单被缓存，不是每轮都查库
        // 【怎么算红】每轮直查 —— 那条 WHERE 是 `tier IS NOT NULL OR override IS NOT NULL`，
        //          app_user 上没有可用索引（全表除主键无索引，OR 条件也走不了索引），
        //          每 2 秒一次 = 每天 43200 次全表扫；而在没有企业客户之前它每次都返回空。
        //          表越大越贵，且这笔开销完全看不见——不会报错、不会告警
        task.reconcileLocked();
        task.reconcileLocked();
        task.reconcileLocked();

        verify(appUserMapper, times(1)).selectList(any(Wrapper.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aNewlyLimitedAccountIsPickedUpAfterTheCacheExpires() {
        // 【测什么】缓存会过期 —— 刚给某账号加限额，最多一个缓存周期后对账就认得它
        // 【怎么算红】缓存永不过期 —— 新签的企业客户在途任务永远补不进登记表，
        //          Redis 和 DB 从此对不上且没有任何机制能修，而这正是对账存在的理由
        task.reconcileLocked();
        ReflectionTestUtils.setField(registry, "cachedAt",
                System.currentTimeMillis()
                        - org.example.seedancegenarate.service.MeteredAccountRegistry.CACHE_MS - 1);

        task.reconcileLocked();

        verify(appUserMapper, times(2)).selectList(any(Wrapper.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aKeyWithAShareIsReconciledEvenWhenItsOwnerHasNoAccountLimit() {
        // 【测什么】某把 key 设了份额、但它的属主账号没有任何限额时，这把 key 照样被对账
        // 【怎么算红】在途查询只按 user_id 过滤 —— 这个属主不在 accounts 里，查不到它的在途任务，
        //          于是对账每轮都把这把 key 登记表里的全部条目当「幽灵」划掉，
        //          这把 key 的份额永远显示 0、永远拦不住任何东西
        Long keyId = 55L;
        when(appUserMapper.selectList(any(Wrapper.class))).thenReturn(List.of()); // 账号侧一个限额都没有
        org.example.seedancegenarate.entity.ApiKey k = new org.example.seedancegenarate.entity.ApiKey();
        k.setId(keyId);
        when(apiKeyMapper.selectList(any(Wrapper.class))).thenReturn(List.of(k));

        VideoTask t = inFlight(77L, 60);
        t.setApiKeyId(keyId);
        when(videoTaskMapper.selectList(any(Wrapper.class))).thenReturn(List.of(t));
        when(admission.snapshotByKey(keyId)).thenReturn(Set.of());

        task.reconcileLocked();

        ArgumentCaptor<List<Long>> c = ArgumentCaptor.forClass(List.class);
        verify(admission).addAllByKey(eq(keyId), c.capture());
        assertEquals(List.of(77L), c.getValue(), "应当把这条补进 key 的份额登记表");
        verify(admission, never()).removeAllByKey(any(), any());

        // mock 不管 WHERE 写得对不对，所以必须直接断言下发的条件里带了 api_key_id
        ArgumentCaptor<Wrapper<VideoTask>> w = ArgumentCaptor.forClass(Wrapper.class);
        verify(videoTaskMapper).selectList(w.capture());
        String sql = w.getValue().getSqlSegment();
        assertTrue(sql.contains("api_key_id"),
                "账号侧没限额时，在途查询必须靠 api_key_id 才找得到这把 key 的任务，实际=" + sql);
    }

    @Test
    @SuppressWarnings("unchecked")
    void keySharesAreTrackedSeparatelyFromTheAccountTotal() {
        // 【测什么】同一条任务同时记进「账号总量」和「这把 key 的份额」两个桶
        // 【怎么算红】只维护一个桶 —— 企业内部分配就失效了：要么 key 份额永远是空的（拦不住），
        //          要么账号总量算不对（个人建 50 把 key 就能绕过总量）
        Long keyId = 55L;
        org.example.seedancegenarate.entity.ApiKey k = new org.example.seedancegenarate.entity.ApiKey();
        k.setId(keyId);
        when(apiKeyMapper.selectList(any(Wrapper.class))).thenReturn(List.of(k));
        VideoTask t = inFlight(88L, 60);
        t.setApiKeyId(keyId);
        when(videoTaskMapper.selectList(any(Wrapper.class))).thenReturn(List.of(t));

        task.reconcileLocked();

        assertEquals(List.of(88L), capturedAdd(), "账号桶要有");
        ArgumentCaptor<List<Long>> c = ArgumentCaptor.forClass(List.class);
        verify(admission).addAllByKey(eq(keyId), c.capture());
        assertEquals(List.of(88L), c.getValue(), "key 桶也要有");
    }

    @Test
    @SuppressWarnings("unchecked")
    void queriesNothingWhenNoLimitIsConfiguredAnywhere() {
        // 【测什么】两侧都没有任何限额时，一次库都不查
        // 【怎么算红】无条件下发那条 IN 查询 —— 空集合下 MyBatis-Plus 要么生成非法的 IN ()，
        //          要么把整个条件跳过，后者等于每 2 秒把全站所有在途任务捞一遍。
        //          没有企业客户时这是常态，不是边角情况
        when(appUserMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        when(apiKeyMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        task.reconcileLocked();

        verify(videoTaskMapper, never()).selectList(any(Wrapper.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void inFlightQueryFiltersByProcessingStatus() {
        // 【测什么】查在途用的条件真的带 status
        // 【怎么算红】漏掉 status 条件 —— 会把这个账号**历史上所有任务**都当成在跑，
        //          对账把几万条历史 id 塞进登记表，客户从此一条也提交不了
        when(videoTaskMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        task.reconcileLocked();

        ArgumentCaptor<Wrapper<VideoTask>> c = ArgumentCaptor.forClass(Wrapper.class);
        verify(videoTaskMapper).selectList(c.capture());
        String sql = c.getValue().getSqlSegment();
        assertTrue(sql.contains("status"), "必须按 PROCESSING 过滤，实际=" + sql);
        assertTrue(sql.contains("user_id"), "必须限定到需要对账的账号，实际=" + sql);
    }
}
