package org.example.seedancegenarate.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "aliyun.oss")
public class OssConfig {
    private String endpoint;
    private String accessKeyId;
    private String accessKeySecret;
    private String bucketName;
    private String domain;
    /** 生成产物对象前缀，与参考图 images/ 分开。 */
    private String artifactPrefix = "outputs";
    /** 签名下载地址有效期（秒）。 */
    private long signedUrlTtlSeconds = 300;
}
