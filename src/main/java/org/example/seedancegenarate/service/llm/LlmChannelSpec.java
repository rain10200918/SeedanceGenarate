package org.example.seedancegenarate.service.llm;

import java.util.Locale;

/**
 * 一条可用的 LLM 通道，{@link LlmChatClient} 唯一认的输入。
 * 由 {@link LlmChannelRegistry} 从表（或 yaml seed）解析出来，不可变。
 *
 * @param temperature null = 请求体里不带
 * @param tokenParam  max_tokens 那个字段用哪个名字发；{@link TokenParam#NONE} = 不发
 */
public record LlmChannelSpec(
        String name,
        String baseUrl,
        String apiKey,
        String model,
        Double temperature,
        int maxTokens,
        TokenParam tokenParam,
        int timeoutMs,
        int priority,
        boolean enabled,
        boolean archived,
        String remark
) {

    /**
     * 前端 axios 的硬墙（src/api/http.ts: timeout 120_000）。任何通道的读超时都必须严格小于它：
     * 超过就是前端先断连、后端还在白烧 token，用户看到的症状和超时一模一样，排查时还会以为后端放宽了。
     * 管理端写入校验和测试都引用这一个数。
     */
    public static final int FRONTEND_TIMEOUT_MS = 120_000;
    public static final int MAX_TIMEOUT_MS = FRONTEND_TIMEOUT_MS - 1_000;
    public static final int MIN_TIMEOUT_MS = 1_000;

    /**
     * 同是「OpenAI 兼容」，最大输出 token 这个字段名分了两派；推理类模型对旧名字直接 400。
     * <b>这是请求形状上唯一允许的加减</b>——再多就是在造模板引擎。
     */
    public enum TokenParam {
        MAX_TOKENS("max_tokens"),
        MAX_COMPLETION_TOKENS("max_completion_tokens"),
        NONE(null);

        private final String field;

        TokenParam(String field) {
            this.field = field;
        }

        /** 请求体里的字段名；NONE 为 null */
        public String field() {
            return field;
        }

        /** 库里存的是小写名（max_tokens / max_completion_tokens / none）；认不出来按 MAX_TOKENS */
        public static TokenParam parse(String stored) {
            if (stored == null) {
                return MAX_TOKENS;
            }
            return switch (stored.trim().toLowerCase(Locale.ROOT)) {
                case "max_completion_tokens" -> MAX_COMPLETION_TOKENS;
                case "none" -> NONE;
                default -> MAX_TOKENS;
            };
        }

        public String stored() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** 参与路由 = 启用且未归档 */
    public boolean routable() {
        return enabled && !archived;
    }

    /**
     * 给人看的密钥形态：前 6 后 2，中间遮住。列表、日志、异常消息里只许出现这个。
     * 短到遮不住的一律整串遮掉——不然 8 位以内的 key 会被原样露出来。
     */
    public static String maskKey(String key) {
        if (key == null || key.isBlank()) {
            return "";
        }
        String k = key.trim();
        if (k.length() <= 8) {
            return "••••••••";
        }
        return k.substring(0, 6) + "••••••" + k.substring(k.length() - 2);
    }
}
