package org.example.seedancegenarate.service.llm;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 在多条 LLM 通道之间路由。业务侧（提示词优化，将来的翻译/打标）只认这个入口，不认具体通道。
 *
 * <h3>降级规则只有两条</h3>
 * <ol>
 *   <li>按 priority 顺序试启用的通道，<b>快失败</b>（连接被拒/连接超时/4xx/5xx/解析失败/空响应）就切下一条</li>
 *   <li><b>读超时直接失败</b>，不切。前端 axios 是 120 秒的硬墙，主通道读超时已经用掉 100 秒，
 *       备通道只剩 20 秒——切过去不是救用户，是让他多等 20 秒然后照样失败。人已拍板</li>
 * </ol>
 * 代价要说清：自建 vLLM <b>变慢但还活着</b>的情况，降级永远不会触发。那是容量问题不是故障，该修的是容量。
 * <p>
 * 每条通道的每次尝试都被 TokenUsageAspect 单独记一行（含失败），所以「这次是谁服务的、之前谁失败了」事后可查。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LlmRouter {

    private final LlmChannelRegistry registry;
    private final LlmChatClient client;

    /** 走路由：按优先级试启用通道 */
    public LlmChatResponse chat(List<Map<String, Object>> messages, LlmCallMeta meta) {
        List<LlmChannelSpec> channels = registry.routable();
        if (channels.isEmpty()) {
            throw new RuntimeException("提示词优化服务未配置，请联系管理员");
        }
        LlmChannelException last = null;
        for (LlmChannelSpec channel : channels) {
            try {
                return client.chat(channel, messages, meta);
            } catch (LlmChannelException e) {
                if (!e.failoverable()) {
                    throw e;
                }
                last = e;
                log.warn("LLM 通道 {} 快失败[{}]，切下一条", channel.name(), e.reason());
            }
        }
        // 全部快失败：抛最后一个。它的 userMessage 就是通用的「请稍后再试」
        throw last;
    }

    /**
     * 指定通道（<b>含停用、含归档</b>），管理端试跑用。绕过 enabled 是刻意的——
     * 新通道的初始状态就是关闭，试跑就是给「开之前先看看输出」用的。
     * 不存在的名字响亮失败，不静默回落路由：否则你以为在测新通道，其实跑在老通道上，然后得出「新通道没问题」。
     */
    public LlmChatResponse chatWith(String channelName, List<Map<String, Object>> messages, LlmCallMeta meta) {
        LlmChannelSpec channel = registry.find(channelName);
        if (channel == null) {
            throw new IllegalArgumentException("LLM 通道不存在: " + channelName);
        }
        return client.chat(channel, messages, meta);
    }
}
