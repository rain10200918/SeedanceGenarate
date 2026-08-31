package org.example.seedancegenarate.engine.comfyui;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 版本漂移告警<b>不许刷屏</b>，而且它的状态<b>必须扛得住并发探测</b>。
 *
 * <h3>这两条都是上线当天的实测暴露的</h3>
 * 2026-08-28 22:38 第一次真启动，日志里出现：
 * <pre>
 *   22:38:21  gpu-7     偏离主流（本机 torch 2.8.0 / 主流 2.13.0）
 *   22:39:19  gpu-spark 偏离主流（本机 torch 2.13.0 / 主流 2.8.0）   ← 和上一条互相矛盾
 *   22:40:19  gpu-spark 偏离主流 ...
 *   22:41:19  gpu-spark 偏离主流 ...   ← 每 60 秒一条，永不停止
 * </pre>
 * 矛盾来自「画面还不完整时就算多数派」（并行探测逐个填表）；
 * 刷屏来自「Spark 是 ARM64，它的技术栈差异<b>永远不会消失</b>」。
 * <p>
 * 后者是 D-028 那条教训的翻版：一条永远为真的告警等于没有告警，
 * 真正的漂移（哪天有人升级了一台 H100）会被埋在这些噪音里。
 */
class VersionDriftNoiseTest {

    private ComfyUiProber prober;

    @BeforeEach
    void setUp() {
        prober = new ComfyUiProber(new ComfyUiProperties(), mock(ComfyNodeRegistry.class),
                mock(ComfyUiClient.class), mock(ComfyUiFleet.class));
    }

    /** 直接摆一台节点的版本，绕开 HTTP */
    @SuppressWarnings("unchecked")
    private void report(String nodeId, String torch) {
        Map<String, String> versions = new LinkedHashMap<>();
        versions.put("comfyui", "0.33.0");
        versions.put("torch", torch);
        ((Map<String, String>) org.springframework.test.util.ReflectionTestUtils
                .getField(prober, "versionFingerprints")).put(nodeId, versions.toString());
    }

    @Test
    void aPermanentlyHeterogeneousFleetIsReportedExactlyOnce() {
        // 【测什么】机队里长期有一台不同（Spark 必须用另一套 torch/python）时只报一次，之后彻底安静
        // 【怎么算红】每轮都比一次、不一样就 WARN —— 上线当天实测每 60 秒刷一条、永远刷下去。
        //          一条永远为真的告警等于没有告警：真正的漂移会被埋在噪音里，
        //          而这条告警存在的唯一理由就是发现真正的漂移
        report("gpu-1", "2.8.0+cu128");
        report("gpu-3", "2.8.0+cu128");
        report("gpu-spark", "2.13.0+cu130");

        String first = prober.driftMessage();
        assertNotNull(first, "第一次发现不一致要说");
        assertTrue(first.contains("gpu-spark"), "要说清是哪台，实际=" + first);

        for (int round = 0; round < 20; round++) {
            assertNull(prober.driftMessage(), "画面没变就一个字都不许再说（第 " + round + " 轮）");
        }
    }

    @Test
    void aUniformFleetSaysNothingAtAll() {
        // 【测什么】机队版本一致时完全不说话
        // 【怎么算红】统一时也报一句「已统一」—— 启动时每台探完各报一遍，
        //          正常状态反而比异常状态更吵
        report("gpu-1", "2.8.0+cu128");
        report("gpu-3", "2.8.0+cu128");
        report("gpu-6", "2.8.0+cu128");

        assertNull(prober.driftMessage(), "全都一样时不该有任何输出");
        assertNull(prober.driftMessage());
    }

    @Test
    void aRealUpgradeBreaksThroughTheSilence() {
        // 【测什么】安静下来之后，真有人升级了一台（画面变了）必须立刻再说一次
        // 【怎么算红】为了不刷屏而改成「只报第一次」—— 那就把这条告警彻底关掉了。
        //            要的是"变化时说"，不是"说过就不说了"
        report("gpu-1", "2.8.0+cu128");
        report("gpu-3", "2.8.0+cu128");
        report("gpu-spark", "2.13.0+cu130");
        assertNotNull(prober.driftMessage());
        assertNull(prober.driftMessage(), "先安静下来");

        report("gpu-3", "2.9.0+cu128"); // 有人升级了 gpu-3

        String message = prober.driftMessage();
        assertNotNull(message, "画面变了必须再说一次");
        assertTrue(message.contains("2.9.0"), "要带上新版本，实际=" + message);
    }

    @Test
    void goingBackToUniformIsAnnouncedOnceThenSilent() {
        // 【测什么】从不一致恢复到一致时说一句「已统一」，然后继续安静
        // 【怎么算红】恢复了不说 —— 运维升级完剩下那台之后，无法确认机队真的对齐了，
        //            只能靠"WARN 不再出现"来推断，而那和"告警坏了"长得一模一样
        report("gpu-1", "2.8.0+cu128");
        report("gpu-spark", "2.13.0+cu130");
        assertNotNull(prober.driftMessage());

        report("gpu-spark", "2.8.0+cu128"); // 对齐了

        String message = prober.driftMessage();
        assertNotNull(message, "恢复一致要说一声");
        assertTrue(message.contains("统一"), "实际=" + message);
        assertNull(prober.driftMessage(), "说完继续安静");
    }

    @Test
    void concurrentProbesDoNotCorruptTheVersionTable() throws Exception {
        // 【测什么】N 个探测线程同时写版本表 + 同时算机队分布，不炸也不丢数据
        // 【怎么算红】用 LinkedHashMap（第一版就是）—— 慢探测把每台节点并行提交到线程池，
        //          一边 put 一边遍历 values() 算分布，是 ConcurrentModificationException
        //          和"读到半更新的表"的教科书场景。而它只在节点数够多、时序够巧时才炸，
        //          单机 6 台可能几个月都不出事，然后在扩到 30 台的那天开始随机失败
        int threads = 24;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger failures = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            int n = i;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int r = 0; r < 200; r++) {
                        report("gpu-" + n, n % 2 == 0 ? "2.8.0" : "2.13.0");
                        prober.driftMessage();
                    }
                } catch (Exception e) {
                    failures.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "并发跑不完，可能死锁了");
        pool.shutdownNow();

        assertEquals(0, failures.get(), "并发读写版本表不该抛异常");
    }
}
