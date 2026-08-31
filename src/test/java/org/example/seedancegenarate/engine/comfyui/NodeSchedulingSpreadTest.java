package org.example.seedancegenarate.engine.comfyui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.seedancegenarate.engine.GenerateCommand;
import org.example.seedancegenarate.engine.ModelSpec;
import org.example.seedancegenarate.engine.comfyui.filter.CapabilityFilter;
import org.example.seedancegenarate.engine.comfyui.filter.EnabledFilter;
import org.example.seedancegenarate.engine.comfyui.filter.HealthyFilter;
import org.example.seedancegenarate.engine.comfyui.filter.VramFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 调度的**意图**守卫：一批连续提交不能因为看不见彼此而全压到同一台。
 *
 * <h3>它替代了 QueueCacheTest.schedulingPathBypassesTheCache</h3>
 * 那一条守的是 D-026 的**机制**（「调度必须每次实时查」），断言「发了 3 次 HTTP」。
 * 机制守卫的问题是：它全绿的时候意图仍然可能是破的 —— 而它当时就是破的。
 *
 * <h3>实时探测在羊群真正形成的窗口里什么都没做</h3>
 * <pre>
 *   pick() 选中 gpu-1
 *      ↓  POST /upload/image     几十 MB，走公网跳板，1~3 秒
 *      ↓  POST /prompt
 *      ↓  gpu-1 的 queue_remaining 这时才 +1
 * </pre>
 * 在这 1~3 秒里，第二次 pick() 去实时探测 gpu-1，拿到的还是 0。探测是真·实时的，
 * 也是真·没用。改造前实测：3 台空闲节点连续 20 次 pick，分布 {@code {gpu-a=20, gpu-b=0, gpu-c=0}}。
 * <p>
 * 所以「看得见自己刚发出去的」比「看得见对方队列的真值」更决定性，
 * 而前者只能靠**同步递增的待发计数**，探测再实时也拿不到。见 D-026（2026-08-28 修订）。
 */
class NodeSchedulingSpreadTest {

    private ComfyUiProperties props;

    @BeforeEach
    void setUp() {
        props = new ComfyUiProperties();
        props.setNodes(List.of(node("gpu-a", 1.0), node("gpu-b", 1.0), node("gpu-c", 1.0)));
    }

    private static ComfyUiProperties.Node node(String id, double weight) {
        ComfyUiProperties.Node n = new ComfyUiProperties.Node();
        n.setId(id);
        n.setBaseUrl("http://node/" + id);
        n.setEnabled(true);
        n.setWeight(weight);
        return n;
    }

    /** 造一份「探测器刚跑完」的快照：depths 里没写的节点视为队列空 */
    private ComfyUiFleet fleetWithDepths(Map<String, Integer> depths) {
        ComfyUiFleet fleet = new ComfyUiFleet(props);
        long now = System.currentTimeMillis();
        Map<String, NodeState> fresh = new LinkedHashMap<>();
        for (ComfyUiProperties.Node n : props.getNodes()) {
            fresh.put(n.getId(),
                    NodeState.initial(n).probedOk(depths.getOrDefault(n.getId(), 0), now, 1L));
        }
        fleet.replace(fresh);
        return fleet;
    }

    /** 真实的过滤链：enabled(10) → healthy(20) → capability(30) → vram(40) */
    private ComfyUiNodeScheduler scheduler(ComfyUiFleet fleet, WorkflowRequirements reqs) {
        return new ComfyUiNodeScheduler(props, fleet, List.of(
                new EnabledFilter(), new HealthyFilter(),
                new CapabilityFilter(reqs), new VramFilter(reqs)));
    }

    private ComfyUiNodeScheduler scheduler(ComfyUiFleet fleet) {
        return scheduler(fleet, requirements(List.of()));
    }

    /** 用真实模板算需求。models 里每项是 {model, templatePath} */
    private WorkflowRequirements requirements(List<String[]> models) {
        List<WorkflowBuilder> builders = models.stream().map(m -> (WorkflowBuilder) new WorkflowBuilder() {
            @Override
            public String model() {
                return m[0];
            }

            @Override
            public ModelSpec spec() {
                return null;
            }

            @Override
            public String templatePath() {
                return m[1];
            }

            @Override
            public JsonNode build(GenerateCommand command, ReferenceFiles files) {
                return null;
            }
        }).toList();
        WorkflowRequirements reqs = new WorkflowRequirements(builders, props, new ObjectMapper());
        reqs.parseTemplates();
        return reqs;
    }

    private Map<String, Integer> pickTimes(ComfyUiNodeScheduler scheduler, int n) {
        Map<String, Integer> hits = new LinkedHashMap<>();
        for (ComfyUiProperties.Node node : props.getNodes()) {
            hits.put(node.getId(), 0);
        }
        for (int i = 0; i < n; i++) {
            hits.merge(scheduler.pick().node().getId(), 1, Integer::sum);
        }
        return hits;
    }

    @Test
    void submissionsInsideOneInFlightWindowSpreadAcrossNodes() {
        // 【测什么】20 次连续提交、期间没有任何 prompt 真的落地（= 上传都还在路上）时，
        //          派活必须分散到 3 台，最忙 − 最闲 ≤ 1
        // 【怎么算红】去掉 pick() 里的 markDispatched，或让 effectiveLoad 不算 pendingCount ——
        //          调度就只看得见「对方队列的当前值」，窗口内每次都是 0，
        //          严格小于让平局永远归第一台，20 次全落 gpu-a。
        //          这就是 D-026 要防、而它原本那套「每次实时探测」防不住的场景
        ComfyUiNodeScheduler scheduler = scheduler(fleetWithDepths(Map.of()));

        Map<String, Integer> hits = pickTimes(scheduler, 20);

        int busiest = hits.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        int idlest = hits.values().stream().mapToInt(Integer::intValue).min().orElse(0);
        assertTrue(busiest - idlest <= 1,
                "20 次提交必须摊平（最忙−最闲≤1），实际分布=" + hits);
    }

    @Test
    void schedulerHoldsNoHttpClientAtAll() {
        // 【测什么】选节点物理上发不出 HTTP —— 这个类里根本没有 ComfyUiClient 字段
        // 【怎么算红】有人在 pick 路径上补一次「保险起见的实时探测」。
        //          代价是 N 台串行、每台 connectTimeoutMs=3000，一台 hang 住就让
        //          每一次提交多等 3 秒，而这个代价随节点数线性涨（5090D 集群是 30 台）。
        //          这是结构性守卫：字段一加回来就红，比任何数值断言都硬
        for (Field f : ComfyUiNodeScheduler.class.getDeclaredFields()) {
            assertNotEquals(ComfyUiClient.class, f.getType(),
                    "pick() 必须零 IO，不许持有 HTTP client（字段 " + f.getName() + "）");
        }
    }

    @Test
    void pickWorksBeforeTheFirstProbeEverRuns() {
        // 【测什么】后端刚启动、探测器还没跑过第一轮时，提交照样能选到节点
        // 【怎么算红】快照初值取「空」或「healthy=false」—— 那样每次重启后的头一个
        //          探测周期内所有提交全部失败，而重启是最常做的运维动作
        ComfyUiNodeScheduler scheduler = scheduler(new ComfyUiFleet(props));

        assertNotNull(scheduler.pick(), "启动后第一次提交不该因为「还没探过」而失败");
    }

    @Test
    void aNodeThatIsGenuinelyBusierGetsLessWork() {
        // 【测什么】队列**真的**不一样时仍然选最闲的 —— 摊平不能把负载均衡本身弄丢
        // 【怎么算红】为了让第一条测试变绿而改成纯轮询（round-robin）——
        //          那样一台已经积压 50 个任务的节点还会继续分到 1/3 的活
        ComfyUiNodeScheduler scheduler =
                scheduler(fleetWithDepths(Map.of("gpu-a", 50)));

        Map<String, Integer> hits = pickTimes(scheduler, 20);

        assertEquals(0, hits.get("gpu-a"),
                "已经积压 50 个的节点不该再分到活，实际分布=" + hits);
    }

    @Test
    void aHalfSpeedNodeGetsAboutHalfTheWork() {
        // 【测什么】weight=0.45 的 Spark 拿到的活约为 H100 的 45%
        // 【怎么算红】effectiveLoad 不除 weight —— Spark 会和 H100 平分（各 1/3），
        //          而它实测慢 2.03~2.34 倍，于是它成为整条流水线的堵点：
        //          用户看到的是「有时候快有时候慢一倍」，而监控上每台节点都很正常。
        //          gpu-spark 已经在生产 yaml 里 enabled，这一条现在就在发生
        props.setNodes(List.of(node("gpu-a", 1.0), node("gpu-b", 1.0), node("gpu-spark", 0.45)));
        ComfyUiNodeScheduler scheduler = scheduler(fleetWithDepths(Map.of()));

        Map<String, Integer> hits = pickTimes(scheduler, 20);

        double ratio = hits.get("gpu-spark") / (double) hits.get("gpu-a");
        assertTrue(ratio > 0.30 && ratio < 0.65,
                "Spark 该拿约 45% 的活（weight 0.45），实际比例=" + ratio + " 分布=" + hits);
    }

    @Test
    void aFailedSubmitReleasesItsPendingSlot() {
        // 【测什么】提交失败后归还名额，这台节点立刻回到候选里
        // 【怎么算红】只加不减 —— 节点出问题时提交失败往往是连续的，
        //          几次下来这台就被晾到老化窗口过完；极端情况所有节点都被自己的
        //          失败计数顶满，明明机器都好着却选不出节点
        ComfyUiFleet fleet = fleetWithDepths(Map.of());
        ComfyUiNodeScheduler scheduler = scheduler(fleet);

        ComfyUiNodeScheduler.NodeSelection first = scheduler.pick();
        assertEquals(1, fleet.pendingCount(first.node().getId()));
        scheduler.releaseDispatch(first);

        assertEquals(0, fleet.pendingCount(first.node().getId()), "提交失败必须归还名额");
        assertEquals(first.node().getId(), scheduler.pick().node().getId(), "归还后这台应重新成为最闲的一台");
    }

    @Test
    void aFailedSubmitReleasesItsOwnReservationNotAnotherUpload() throws Exception {
        // 【测什么】后发的 B 提交失败时，只释放 B 自己；较早仍在上传的 A 保留到自己的老化时刻
        // 【怎么算红】releaseDispatch 只按 nodeId pollFirst —— B 失败会误删最早的 A，
        //          留下 B 的新时间戳；等 A 本该老化时 pending 仍为 1，节点负载被凭空抬高
        props.setNodes(List.of(node("gpu-only", 1.0)));
        props.setProbeIntervalMs(1);
        props.setPendingAgingMs(100);
        ComfyUiFleet fleet = fleetWithDepths(Map.of());
        ComfyUiNodeScheduler scheduler = scheduler(fleet);

        scheduler.pick(); // A：仍在上传
        Thread.sleep(80);
        ComfyUiNodeScheduler.NodeSelection failedB = scheduler.pick();
        scheduler.releaseDispatch(failedB);
        Thread.sleep(40);

        assertEquals(0, fleet.pendingCount("gpu-only"), "B 已释放，A 也已到自己的老化时刻");
    }

    @Test
    void concurrentPicksAndExactReleasesKeepEveryReservation() throws Exception {
        // 【测什么】同一节点 200 个并发 pick 全部登记，随后并发精确释放后归零
        // 【怎么算红】pending 的值仍用 ArrayDeque 且 addLast 不加锁 —— ConcurrentHashMap 只保护 Map，
        //          不保护 deque；并发写会丢元素或破坏头尾索引，计数不再等于 200
        props.setNodes(List.of(node("gpu-only", 1.0)));
        ComfyUiFleet fleet = fleetWithDepths(Map.of());
        ComfyUiNodeScheduler scheduler = scheduler(fleet);
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            List<Future<ComfyUiNodeScheduler.NodeSelection>> picks = new java.util.ArrayList<>();
            for (int i = 0; i < 200; i++) {
                picks.add(pool.submit(() -> scheduler.pick()));
            }
            List<ComfyUiNodeScheduler.NodeSelection> selections = new java.util.ArrayList<>();
            for (Future<ComfyUiNodeScheduler.NodeSelection> pick : picks) {
                selections.add(pick.get());
            }
            assertEquals(200, fleet.pendingCount("gpu-only"), "每次 pick 都必须留下一个待发预约");

            List<Future<?>> releases = new java.util.ArrayList<>();
            for (ComfyUiNodeScheduler.NodeSelection selection : selections) {
                releases.add(pool.submit(() -> scheduler.releaseDispatch(selection)));
            }
            for (Future<?> release : releases) {
                release.get();
            }
            assertEquals(0, fleet.pendingCount("gpu-only"), "每个预约精确释放后必须归零");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void pendingCountAgesOutSoACrashCannotPoisonANode() throws Exception {
        // 【测什么】没有人归还时，待发计数也会自己过期
        // 【怎么算红】没有老化 —— 一次进程被 kill -9、一条我们没想到的异常路径，
        //          留下的计数就永久挂在那台节点上，它从此显得比实际忙。
        //          泄漏只增不减，最后所有节点都"很忙"（D-031 里计数器那个坑的同一形状）
        props.setProbeIntervalMs(1);
        props.setPendingAgingMs(1); // 实际生效值 = max(1, 1×2) = 2ms
        ComfyUiFleet fleet = fleetWithDepths(Map.of());
        ComfyUiNodeScheduler scheduler = scheduler(fleet);

        String picked = scheduler.pick().node().getId();
        assertEquals(1, fleet.pendingCount(picked));
        Thread.sleep(30);

        assertEquals(0, fleet.pendingCount(picked), "过了老化窗口的待发计数必须自己消失");
    }

    @Test
    void allNodesUnhealthyFallsBackToTheWholePoolInsteadOfRefusing() {
        // 【测什么】所有节点都探测失败时降级为「全部候选」，而不是全线拒绝提交
        // 【怎么算红】healthy 过滤之后直接抛「所有 ComfyUI 节点均不可用」——
        //          一次跳板机抖动 / 探测器自己出问题，就是整站提交不了。
        //          派给一台可能病着的机器最坏是提交失败 → markFailed → 解冻，钱不会错；
        //          全线拒绝没有任何补救。宁可慢，不可全站停摆
        ComfyUiFleet fleet = new ComfyUiFleet(props);
        Map<String, NodeState> allDown = new LinkedHashMap<>();
        for (ComfyUiProperties.Node n : props.getNodes()) {
            allDown.put(n.getId(), NodeState.initial(n).probeFailed("502", 1, System.currentTimeMillis()));
        }
        fleet.replace(allDown);

        assertNotNull(scheduler(fleet).pick(),
                "全部不健康时应降级为全部候选，而不是拒绝服务");
    }

    @Test
    void aZeroWeightNodeCannotSwallowTheWholeFleet() {
        // 【测什么】weight 配成 0 时不会把全站的活都吸过去
        // 【怎么算红】直接用 weight 做除数 —— 0 会除出 Infinity/NaN，
        //          在 `load < bestLoad` 下 NaN 永远为假、Infinity 永远最大…… 反过来配 -1
        //          就得到负的有效负载，那台节点从此永远"最闲"，20 次全落它身上。
        //          而这只是有人在 yaml 里少打了一个小数点
        props.setNodes(List.of(node("gpu-a", 1.0), node("gpu-b", 1.0), node("gpu-bad", 0.0)));
        ComfyUiNodeScheduler scheduler = scheduler(fleetWithDepths(Map.of()));

        Map<String, Integer> hits = pickTimes(scheduler, 20);

        assertTrue(hits.get("gpu-bad") <= 2,
                "weight=0 的节点不该吸走全部流量，实际分布=" + hits);
    }

    // ---------- 能力路由：机器不再同构，跑不了的要在 pick 阶段就排除 ----------

    private static final String Z_TURBO = "z-image-turbo";
    private static final String Z_TURBO_TEMPLATE = "comfyui/workflows/z-image-turbo.json";
    private static final long GIB = 1024L * 1024 * 1024;

    private NodeState state(String id, Set<String> caps, Long vramBytes) {
        ComfyUiProperties.Node n = props.getNodes().stream()
                .filter(x -> x.getId().equals(id)).findFirst().orElseThrow();
        return NodeState.initial(n).probedOk(0, System.currentTimeMillis(), 1L)
                .withCapabilities(caps, vramBytes, "TEST-GPU", Map.of());
    }

    private ComfyUiFleet fleetWith(Map<String, NodeState> states) {
        ComfyUiFleet fleet = new ComfyUiFleet(props);
        fleet.replace(states);
        return fleet;
    }

    @Test
    void aNodeMissingOnePluginNeverGetsThatModel() {
        // 【测什么】装不齐这个工作流用到的 node type 的机器，在 pick 阶段就被排除
        // 【怎么算红】不做能力过滤（今天的行为）—— 活派过去，ComfyUI 返回 missing_node_type，
        //          任务失败、退款、用户重试，再随机撞到那台机器上。
        //          机器同构时这不存在，而 Spark 是 ARM64、5090D 只有 32G，
        //          "所有节点装一样的东西"从这一刻起就是做不到的事
        WorkflowRequirements reqs = requirements(List.<String[]>of(new String[]{Z_TURBO, Z_TURBO_TEMPLATE}));
        Set<String> full = reqs.nodeTypesFor(Z_TURBO);
        assertNotNull(full, "模板应该能解析出 node type，否则这条测试是空过的");
        assertTrue(full.size() >= 3, "模板里的 node type 太少，测不出东西: " + full);

        Set<String> crippled = new LinkedHashSet<>(full);
        String removed = crippled.iterator().next();
        crippled.remove(removed);

        ComfyUiFleet fleet = fleetWith(Map.of(
                "gpu-a", state("gpu-a", crippled, 80 * GIB),
                "gpu-b", state("gpu-b", full, 80 * GIB),
                "gpu-c", state("gpu-c", crippled, 80 * GIB)));
        ComfyUiNodeScheduler scheduler = scheduler(fleet, reqs);

        for (int i = 0; i < 10; i++) {
            assertEquals("gpu-b", scheduler.pick(Z_TURBO).node().getId(),
                    "只有 gpu-b 装齐了，缺 " + removed + " 的机器不该拿到活");
        }
    }

    @Test
    void theErrorNamesEveryNodeAndWhyItWasRuledOut() {
        // 【测什么】一台都选不出来时，异常信息里逐节点写明淘汰理由
        // 【怎么算红】继续抛「所有 ComfyUI 节点均不可用」—— 那句话查不出任何东西。
        //          2026-08-27 生产日志里 gpu-0 一直 502，是靠人翻 WARN 才发现的；
        //          节点从 5 台涨到 30 台之后，靠翻日志归因不再可行
        WorkflowRequirements reqs = requirements(List.<String[]>of(new String[]{Z_TURBO, Z_TURBO_TEMPLATE}));
        props.setModelMinVramGib(Map.of(Z_TURBO, 53.0));
        props.getNodes().get(0).setEnabled(false);

        ComfyUiFleet fleet = fleetWith(Map.of(
                "gpu-a", state("gpu-a", reqs.nodeTypesFor(Z_TURBO), 80 * GIB),
                "gpu-b", state("gpu-b", Set.of("SomethingElse"), 80 * GIB),
                "gpu-c", state("gpu-c", reqs.nodeTypesFor(Z_TURBO), 32 * GIB)));

        String message = assertThrows(RuntimeException.class,
                () -> scheduler(fleet, reqs).pick(Z_TURBO)).getMessage();

        assertTrue(message.contains("gpu-a") && message.contains("未启用"),
                "该说 gpu-a 是被人关掉的，实际=" + message);
        assertTrue(message.contains("gpu-b") && message.contains("node type"),
                "该说 gpu-b 缺哪些 node type，实际=" + message);
        assertTrue(message.contains("gpu-c") && message.contains("显存"),
                "该说 gpu-c 显存不够，实际=" + message);
    }

    @Test
    void aNodeTooSmallForTheModelIsExcludedEvenWhenItIsIdle() {
        // 【测什么】32 GiB 的机器跑不了要 53 GiB 的模型，哪怕它此刻最闲
        // 【怎么算红】按 vram_free 而不是 vram_total 过滤 —— 那会变成
        //          "这台现在忙所以它跑不了 minimax"，把一个排队问题误判成能力问题；
        //          等它闲下来又放行，于是同一台机器时好时坏。物理上限才是判据
        WorkflowRequirements reqs = requirements(List.<String[]>of(new String[]{Z_TURBO, Z_TURBO_TEMPLATE}));
        props.setModelMinVramGib(Map.of(Z_TURBO, 53.0));
        Set<String> full = reqs.nodeTypesFor(Z_TURBO);

        ComfyUiFleet fleet = fleetWith(Map.of(
                "gpu-a", state("gpu-a", full, 32 * GIB),   // 最闲，但装不下
                "gpu-b", state("gpu-b", full, 80 * GIB),
                "gpu-c", state("gpu-c", full, 32 * GIB)));
        ComfyUiNodeScheduler scheduler = scheduler(fleet, reqs);

        for (int i = 0; i < 10; i++) {
            assertEquals("gpu-b", scheduler.pick(Z_TURBO).node().getId(), "显存装不下的机器不该拿到活");
        }
    }

    @Test
    void aNodeWhoseCapabilitiesAreUnknownIsStillUsable() {
        // 【测什么】能力还没探到（启动后头一分钟 / /object_info 拉失败）时**放行**
        // 【怎么算红】把 null 当成空集 —— 一次 /object_info 超时就把整台机器判成
        //          "什么都跑不了"，而慢探测是 60 秒一轮：一次抖动等于摘掉这台机器一分钟。
        //          更糟的是后端刚重启时所有节点的能力都是 null，那样全站提交不了
        WorkflowRequirements reqs = requirements(List.<String[]>of(new String[]{Z_TURBO, Z_TURBO_TEMPLATE}));
        props.setModelMinVramGib(Map.of(Z_TURBO, 53.0));

        ComfyUiFleet fleet = fleetWith(Map.of(
                "gpu-a", state("gpu-a", null, null),
                "gpu-b", state("gpu-b", null, null),
                "gpu-c", state("gpu-c", null, null)));

        assertNotNull(scheduler(fleet, reqs).pick(Z_TURBO),
                "能力未知 ≠ 能力为空，未知时应退回「不做检查」");
    }

    @Test
    void aHardConstraintIsNeverWaivedEvenWhenNothingSurvives() {
        // 【测什么】全灭时放弃的只有「健康」这一条软条件，缺插件 / 显存不够 / 人工关闭照旧拦着
        // 【怎么算红】降级时把所有过滤都丢掉 —— 那只是把一次「选不出节点」换成
        //          一次「提交过去必然失败」：钱先冻结、上传几十 MB、ComfyUI 报错、退款。
        //          用户看到的是莫名其妙的失败，而日志里连"为什么选了这台"都没有
        WorkflowRequirements reqs = requirements(List.<String[]>of(new String[]{Z_TURBO, Z_TURBO_TEMPLATE}));
        ComfyUiFleet fleet = fleetWith(Map.of(
                "gpu-a", state("gpu-a", Set.of("SomethingElse"), 80 * GIB)
                        .probeFailed("502", 1, System.currentTimeMillis()),
                "gpu-b", state("gpu-b", Set.of("SomethingElse"), 80 * GIB)
                        .probeFailed("502", 1, System.currentTimeMillis()),
                "gpu-c", state("gpu-c", Set.of("SomethingElse"), 80 * GIB)
                        .probeFailed("502", 1, System.currentTimeMillis())));

        String message = assertThrows(RuntimeException.class,
                () -> scheduler(fleet, reqs).pick(Z_TURBO)).getMessage();

        assertTrue(message.contains("node type"),
                "既不健康又缺插件时，缺插件这条硬约束不该被降级放过，实际=" + message);
    }

    // ---------- 指定节点提交：新机器先验证再放量 ----------

    @Test
    void aPinnedNodeIsUsedEvenWhenItIsTurnedOff() {
        // 【测什么】管理员指定的节点即使 enabled=false 也照用
        // 【怎么算红】指定节点也走 enabled 过滤 —— 而新机器接进来的初始状态**就是**关闭的，
        //          这个功能在它唯一有用的场景（先跑通再放量）里恰好不能用。
        //          那样验证新机器就只能"先开给所有真实用户，出问题再关"
        props.getNodes().get(2).setEnabled(false); // gpu-c 是刚接进来的新机器
        ComfyUiFleet fleet = fleetWithDepths(Map.of());

        assertEquals("gpu-c", scheduler(fleet).pick(null, "gpu-c").node().getId(),
                "指定节点必须能指到关闭的机器上");
    }

    @Test
    void aPinnedNodeStillCountsTowardsItsPendingLoad() {
        // 【测什么】指定提交也计入待发计数
        // 【怎么算红】指定路径忘了 markDispatched —— 管理员连点 10 次灰度验证，
        //          这台机器在调度眼里始终是"最闲的"，正常流量会一起压过来，
        //          于是灰度变成了压测
        ComfyUiFleet fleet = fleetWithDepths(Map.of());
        ComfyUiNodeScheduler scheduler = scheduler(fleet);

        scheduler.pick(null, "gpu-c");
        scheduler.pick(null, "gpu-c");

        assertEquals(2, fleet.pendingCount("gpu-c"), "指定提交同样要占住名额");
    }

    @Test
    void pinningANodeThatDoesNotExistFailsLoudly() {
        // 【测什么】指定一个不存在的节点 id 时立刻报错，且错误信息里带上那个 id
        // 【怎么算红】静默回退到正常调度 —— 管理员以为在验证新机器，
        //          实际活跑在老机器上，然后得出"新机器没问题"的结论。
        //          打错一个字母的代价不该是一个错误的结论
        String message = assertThrows(RuntimeException.class,
                () -> scheduler(fleetWithDepths(Map.of())).pick(null, "gpu-typo")).getMessage();

        assertTrue(message.contains("gpu-typo"), "错误信息该带上那个不存在的 id，实际=" + message);
    }
}
