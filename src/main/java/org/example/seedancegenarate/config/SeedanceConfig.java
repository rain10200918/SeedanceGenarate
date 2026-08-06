package org.example.seedancegenarate.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "seedance")
public class SeedanceConfig {
    /**
     * 火山方舟 API Key
     */
    private String apiKey;
    /**
     * 单模型模式下的模型ID / Endpoint ID（未配置 {@link #models} 时使用；请求里的 model 参数无效）
     */
    private String model;
    /**
     * 创建视频任务接口
     */
    private String url;
    /**
     * 多模型模式：配置后前端可选多个 Seedance 模型。
     * <ul>
     *   <li>{@code id}：注册标识（/options 下发、模型开放闸门的 key，如 "seedance" / "seedance-fast"）</li>
     *   <li>{@code name}：火山方舟 API 的模型名（请求 body 的 model 字段）</li>
     *   <li>{@code label}：前端展示名</li>
     * </ul>
     * 为空时回退为单模型 [{@code id:"seedance", name: model}]。
     */
    private List<SeedanceModel> models = List.of();

    @Data
    public static class SeedanceModel {
        private String id;
        private String name;
        private String label;
    }
}
