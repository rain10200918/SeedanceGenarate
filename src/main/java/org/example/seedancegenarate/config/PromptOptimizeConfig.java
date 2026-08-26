package org.example.seedancegenarate.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 提示词优化（OpenAI 兼容 Chat Completions 服务）配置。
 * 密钥仅存在于后端，绝不下发给前端。
 */
@Data
@Component
@ConfigurationProperties(prefix = "prompt-optimize")
public class PromptOptimizeConfig {
    /**
     * Chat Completions 接口地址
     */
    private String url;
    /**
     * Bearer API Key（仅后端持有）
     */
    private String apiKey;
    /**
     * 模型 ID
     */
    private String model;
    /**
     * 采样温度
     */
    private Double temperature = 0.7;
    /**
     * 最大生成 token 数。H3 模板要求完整英文六段结构（detailed_description 300-500 词），默认 1500 才够容纳；旧默认 512 会截断。
     */
    private Integer maxTokens = 1500;
    /**
     * 请求超时时间（毫秒）。
     * <p>
     * 慢的不是网络是出字：实测 1199 个 completion token 要 ≈65s（≈18 tok/s），
     * 而 maxTokens 上限 1500 → 最坏 ≈85s，旧的 60s 会把长提示词一律判死。
     * <b>上限受前端约束</b>：axios 那边是 120s，这里必须留在它以内，
     * 否则前端先断连，后端还在白烧 token，用户看到的症状和超时一模一样。
     */
    private Integer timeoutMs = 100000;
}
