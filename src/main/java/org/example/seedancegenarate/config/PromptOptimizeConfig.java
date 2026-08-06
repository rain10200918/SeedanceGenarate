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
     * 最大生成 token 数
     */
    private Integer maxTokens = 512;
    /**
     * 请求超时时间（毫秒）
     */
    private Integer timeoutMs = 60000;
}
