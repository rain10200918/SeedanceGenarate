package org.example.seedancegenarate.engine.comfyui;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code /queue} 的短缓存守卫。
 * <p>
 * 它的响应里带着队列中<b>每个 prompt 的完整工作流 JSON</b>，可能几 MB。丢失判定会对每个
 * history 为空的任务查一次队列——ComfyUI 回到 2 秒轮询器之后，同一节点上的多条任务
 * 每轮各取一份完全一样的几 MB，纯属烧带宽。
 * <p>
 * 但<b>调度选节点绝不能吃缓存</b>：那是提交路径，用过期负载会让一批提交全压到同一台。
 */
class QueueCacheTest {

    private final ObjectMapper json = new ObjectMapper();
    private AtomicInteger httpCalls;
    private ComfyUiProperties props;
    private ComfyUiClient client;

    /** 只统计真正发出的 HTTP 次数，其余走真实实现 */
    private ComfyUiClient countingClient(ComfyUiProperties properties) {
        return new ComfyUiClient(json, properties) {
            @Override
            public com.fasterxml.jackson.databind.JsonNode getQueue(String baseUrl, int timeoutMs)
                    throws Exception {
                httpCalls.incrementAndGet();
                return json.readTree("{\"queue_running\":[],\"queue_pending\":[]}");
            }
        };
    }

    @BeforeEach
    void setUp() {
        httpCalls = new AtomicInteger();
        props = new ComfyUiProperties();
        client = countingClient(props);
    }

    @Test
    void sameNodeWithinTtlHitsTheCache() throws Exception {
        // 【测什么】TTL 内对同一节点的多次查询只发一次 HTTP
        // 【怎么算红】每次都发 —— 一轮里 5 条任务打同一节点就取 5 份几 MB 的完整工作流，
        //            2 秒轮询下这是持续的带宽浪费
        props.setQueueCacheMs(3000);

        for (int i = 0; i < 5; i++) {
            client.getQueueCached("http://node/gpu-3", 5000);
        }

        assertEquals(1, httpCalls.get(), "实际发出 " + httpCalls.get() + " 次");
    }

    @Test
    void differentNodesAreCachedSeparately() throws Exception {
        // 【测什么】缓存按节点分开，不能串
        // 【怎么算红】共用一份 —— gpu-1 的队列被当成 gpu-3 的，
        //            丢失判定会把在 gpu-3 上排队的任务判成丢失并重投
        props.setQueueCacheMs(3000);

        client.getQueueCached("http://node/gpu-1", 5000);
        client.getQueueCached("http://node/gpu-3", 5000);
        client.getQueueCached("http://node/gpu-1", 5000);

        assertEquals(2, httpCalls.get(), "两个节点各一次，实际=" + httpCalls.get());
    }

    @Test
    void zeroTtlDisablesCaching() throws Exception {
        // 【测什么】TTL 配 0 时退化成每次实时查询（合法的关闭开关）
        // 【怎么算红】0 被当成"永不过期" —— 队列快照永远不更新，
        //            排队中的任务会被一直判成"不在队列"，全部误判丢失重投
        props.setQueueCacheMs(0);

        client.getQueueCached("http://node/gpu-3", 5000);
        client.getQueueCached("http://node/gpu-3", 5000);

        assertEquals(2, httpCalls.get());
    }

    @Test
    void expiredEntryIsRefetched() throws Exception {
        // 【测什么】过期后重新取，不是一直用旧的
        // 【怎么算红】永不过期 —— 同上，队列快照僵死，误判丢失
        props.setQueueCacheMs(1); // 1ms

        client.getQueueCached("http://node/gpu-3", 5000);
        Thread.sleep(20);
        client.getQueueCached("http://node/gpu-3", 5000);

        assertEquals(2, httpCalls.get());
    }

    @Test
    void schedulingPathBypassesTheCache() throws Exception {
        // 【测什么】调度用的 queueLoad 走不带缓存的 getQueue —— 提交路径要看实时负载
        // 【怎么算红】调度也吃缓存 —— 3 秒内的一批提交按同一份过期负载决策，
        //            全部压到同一个节点上，负载均衡形同虚设
        props.setQueueCacheMs(3000);

        client.queueLoad("http://node/gpu-3", 3000);
        client.queueLoad("http://node/gpu-3", 3000);
        client.queueLoad("http://node/gpu-3", 3000);

        assertEquals(3, httpCalls.get(), "调度必须每次实时，实际=" + httpCalls.get());
    }
}
