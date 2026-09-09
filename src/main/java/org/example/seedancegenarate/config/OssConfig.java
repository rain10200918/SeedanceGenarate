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
    /** API 客户端直传的 staging 前缀，应在 OSS 配置短周期生命周期规则。 */
    private String apiUploadPrefix = "api-uploads";
    /** POST Policy 有效期（秒）。 */
    private long apiUploadPolicyTtlSeconds = 600;
    /** 供紧接着提交生成任务使用的签名 GET URL 有效期（秒）。 */
    private long apiUploadReadTtlSeconds = 3600;
    /** API 单个直传文件最大字节数，P0 仅支持图片。 */
    private long apiUploadMaxBytes = 30L * 1024 * 1024;
}
