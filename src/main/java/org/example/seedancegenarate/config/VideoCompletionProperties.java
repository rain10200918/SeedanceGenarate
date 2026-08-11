package org.example.seedancegenarate.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 事件驱动（CALLBACK）引擎的回调配置。 */
@Data
@Component
@ConfigurationProperties(prefix = "video.completion")
public class VideoCompletionProperties {
    /** 回调公网基址（提供方必须能访问）；为空时事件驱动引擎不注入回调、回退轮询。 */
    private String callbackBaseUrl;
    /** 回调鉴权 token（防伪造回调）。 */
    private String callbackSecret;
}
