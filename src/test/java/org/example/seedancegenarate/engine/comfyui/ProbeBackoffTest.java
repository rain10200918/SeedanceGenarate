package org.example.seedancegenarate.engine.comfyui;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 探测侧的熔断：已确诊不健康的节点按指数退避，不再每 3 秒死探。
 *
 * <h3>熔断的是「多久探一次」，不是「派不派活」</h3>
 * 派不派活由 {@code healthy} + {@code HealthyFilter} 决定，和这里完全分开。
 * 合成一件事会致命：被熔断的节点从此不再被探测，于是它<b>永远不会恢复</b>，
 * 修好了也没人知道 —— 而这恰恰是熔断器最常见的实现错误。
 *
 * <h3>为什么需要它</h3>
 * 2026-08-28 生产实测：{@code gpu-0} 指向一个没有进程的端口，nginx 稳定回 502。
 * 没有退避的话它被每 3 秒探一次、一天 28800 次，永远如此。
 * 6 台时可以忽略，30 台里有 10 台死的时候就是 10 个线程在 3 秒周期里空转。
 */
class ProbeBackoffTest {

    private static final long BASE = 3000L;
    private static final long MAX = 60000L;
    private static final int UNHEALTHY_AFTER = 3;

    private ComfyUiProperties props;

    @BeforeEach
    void setUp() {
        props = new ComfyUiProperties();
        props.setNodes(List.of(node("gpu-0", "http://node/gpu-0")));
    }

    private static ComfyUiProperties.Node node(String id, String url) {
        ComfyUiProperties.Node n = new ComfyUiProperties.Node();
        n.setId(id);
        n.setBaseUrl(url);
        n.setEnabled(true);
        return n;
    }

    private NodeState failedTimes(int times, long lastProbedAt) {
        NodeState state = NodeState.initial(props.getNodes().get(0));
        for (int i = 0; i < times; i++) {
            state = state.probeFailed("502", UNHEALTHY_AFTER, lastProbedAt);
        }
        return state;
    }

    @Test
    void aNodeIsProbedAtFullSpeedUntilItIsActuallyDiagnosed() {
        // 【测什么】失败次数没到摘除阈值之前不退避，每轮都探
        // 【怎么算红】第一次失败就开始退避 —— 那几次探测正是在分辨「网络抖了一下」
        //          还是「真死了」，退避会把这个判断拖慢好几倍：
        //          一台只是抖了一下的机器要更久才能被确认还活着，期间它一直不接活
        long now = 1_000_000L;
        for (int failures = 0; failures < UNHEALTHY_AFTER; failures++) {
            NodeState state = failedTimes(failures, now);
            assertTrue(state.dueForProbe(now, BASE, MAX, UNHEALTHY_AFTER),
                    "失败 " + failures + " 次（还没确诊）时应该全速探");
        }
    }

    @Test
    void aConfirmedDeadNodeBacksOffExponentiallyAndCapsOut() {
        // 【测什么】确诊后 3s→6→12→24→48→60 封顶
        // 【怎么算红】(a) 不退避 —— gpu-0 这种永久 502 的节点一天被探 28800 次，
        //              30 台机队里有 10 台死的就是 10 个线程在 3 秒周期里空转；
        //          (b) 不封顶 —— 退到几小时一探，一台修好的机器要等到下个工作日才被发现
        long probedAt = 1_000_000L;
        long[] expected = {6000, 12000, 24000, 48000, 60000, 60000, 60000};

        for (int i = 0; i < expected.length; i++) {
            NodeState state = failedTimes(UNHEALTHY_AFTER + i, probedAt);
            long wait = expected[i];
            assertFalse(state.dueForProbe(probedAt + wait - 1, BASE, MAX, UNHEALTHY_AFTER),
                    "失败 " + (UNHEALTHY_AFTER + i) + " 次时，还差 1ms 不该探");
            assertTrue(state.dueForProbe(probedAt + wait, BASE, MAX, UNHEALTHY_AFTER),
                    "失败 " + (UNHEALTHY_AFTER + i) + " 次时，到 " + wait + "ms 该探了");
        }
    }

    @Test
    void oneSuccessRestoresFullSpeedImmediately() {
        // 【测什么】一次成功立刻回到全速，不搞半开、不渐进恢复
        // 【怎么算红】学经典熔断器做半开/渐进恢复 —— 那是为了保护下游，
        //          而这里的下游只是一个 37 字节的 GET /prompt，探它没有任何代价。
        //          慢恢复的唯一效果是让一台已经修好的机器白白闲着
        long now = 1_000_000L;
        NodeState recovered = failedTimes(10, now).probedOk(0, now, 5L);

        assertEquals(0, recovered.consecutiveFailures());
        assertTrue(recovered.dueForProbe(now, BASE, MAX, UNHEALTHY_AFTER), "恢复后应立刻全速");
    }

    @Test
    void aSkippedRoundDoesNotCountAsAFailure() {
        // 【测什么】退避期间被跳过的节点，状态原样保留：失败计数不涨、lastError 不被改写、
        //          lastProbedAt 不刷新
        // 【怎么算红】把「这轮没探」当成「这轮探失败了」（第一版就是这么写的：r==null → probeFailed("未探测")）。
        //          三个后果叠加：① 失败计数每轮都 +1，永远钉死在最大退避上，
        //          再也退不回来；② lastError 从真正的病因「502」被改写成「未探测」，
        //          运维在页面上看到的是症状不是病因；③ lastProbedAt 每轮刷新，
        //          退避间隔正是拿它算的，等于每轮把退避时钟归零 —— 退避彻底失效
        ComfyUiFleet fleet = new ComfyUiFleet(props);
        long before = System.currentTimeMillis();
        Map<String, NodeState> seed = new LinkedHashMap<>();
        seed.put("gpu-0", failedTimes(5, before));
        fleet.replace(seed);

        // 这一轮把它跳过了：configured 里有它，results 里没有
        fleet.applyQueueProbe(props.getNodes(), List.of(), UNHEALTHY_AFTER);

        NodeState after = fleet.node("gpu-0");
        assertNotNull(after);
        assertEquals(5, after.consecutiveFailures(), "跳过不是失败，计数不该涨");
        assertEquals("502", after.lastError(), "不该把真正的病因改写成「未探测」");
        assertEquals(before, after.lastProbedAt(), "没探就不该刷新探测时刻，否则退避时钟被归零");
    }

    @Test
    void fixingTheAddressClearsTheBackoffAndTheStaleCapabilities() {
        // 【测什么】管理端把地址改对之后，失败计数 / 能力 / 显存全部清零重来
        // 【怎么算红】只换 baseUrl、保留观测态 —— 两个后果：
        //          ① 那台还挂着 5 次失败，继续按 60 秒退避，运维改完看不到任何变化，
        //             只会以为「改了没用」，然后去重启后端；
        //          ② capabilities/vramTotal 还是**旧机器**的，拿它给新地址做能力路由
        //             是错的：新机器可能根本没装那些插件，活派过去必然失败
        NodeState stale = failedTimes(5, 1_000_000L)
                .withCapabilities(Set.of("OldNodeType"), 32L * 1024 * 1024 * 1024, "OLD-GPU", Map.of());

        NodeState fixed = stale.withConfig(node("gpu-0", "http://node/gpu-0-CORRECTED"));

        assertEquals(0, fixed.consecutiveFailures(), "地址改了等于换了台机器，失败计数该清零");
        assertTrue(fixed.healthy(), "新地址应按乐观初值对待");
        assertEquals(null, fixed.capabilities(), "旧机器的能力不该继承给新地址");
        assertEquals(null, fixed.vramTotal(), "旧机器的显存不该继承给新地址");
        assertTrue(fixed.dueForProbe(1_000_000L, BASE, MAX, UNHEALTHY_AFTER), "该立刻重探");
    }

    @Test
    void changingOnlyTheWeightKeepsEverythingObserved() {
        // 【测什么】只改权重/开关/备注时，观测态照旧保留
        // 【怎么算红】withConfig 一律 initial() —— 每次管理端改个备注，
        //          整个机队的能力集合和健康状态全部清空重探，
        //          而能力探测是 60 秒一轮：改个字导致一分钟内能力过滤全失效
        NodeState observed = failedTimes(1, 1_000_000L)
                .withCapabilities(Set.of("SomeNodeType"), 80L * 1024 * 1024 * 1024, "H100", Map.of());

        ComfyUiProperties.Node reweighted = node("gpu-0", "http://node/gpu-0");
        reweighted.setWeight(0.45);
        NodeState after = observed.withConfig(reweighted);

        assertEquals(0.45, after.weight(), 0.001);
        assertEquals(Set.of("SomeNodeType"), after.capabilities(), "地址没变，能力该留着");
        assertEquals(1, after.consecutiveFailures(), "地址没变，失败计数该留着");
    }

    @Test
    void archivingStopsProbesButKeepsTheNodeAvailableForInFlightPolling() throws Exception {
        // 【测什么】归档节点不再发健康探测，但仍留在 Fleet 快照中供已有任务按 node_id 找回
        // 【怎么算红】把归档过滤后的 active 列表交给 applyQueueProbe 整体替换快照 ——
        //          归档后下一轮快探测就把节点删出 Fleet，已有任务 poll 直接判 FAILED 并退款，
        //          但 GPU 上的 prompt 仍继续跑成孤儿（本次修复前就是这个行为）
        ComfyUiProperties.Node active = node("gpu-live", "http://node/live");
        ComfyUiProperties.Node archived = node("gpu-old", "http://node/old");
        archived.setEnabled(false);
        archived.setArchived(true);
        props.setNodes(List.of(active, archived));

        ComfyUiFleet fleet = new ComfyUiFleet(props);
        ComfyNodeRegistry registry = mock(ComfyNodeRegistry.class);
        ComfyUiClient client = mock(ComfyUiClient.class);
        when(registry.nodes()).thenReturn(props.getNodes());
        when(client.queueRemaining("http://node/live", props.getConnectTimeoutMs())).thenReturn(0);

        new ComfyUiProber(props, registry, client, fleet).probeQueues();

        assertNotNull(fleet.findNode("gpu-old"), "归档节点必须留给在途任务轮询");
        verify(client, never()).queueRemaining(org.mockito.ArgumentMatchers.eq("http://node/old"), anyInt());
    }
}
