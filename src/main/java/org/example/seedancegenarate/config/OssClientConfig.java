package org.example.seedancegenarate.config;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 统一管理线程安全的 OSS 客户端，参考图和生成产物共用连接池。 */
@Configuration
public class OssClientConfig {

    @Bean(destroyMethod = "shutdown")
    public OSS ossClient(OssConfig ossConfig) {
        String endpoint = ossConfig.getEndpoint();
        if (endpoint != null && !endpoint.isBlank()) {
            endpoint = endpoint.trim();
            if (!endpoint.matches("(?i)^https?://.*")) {
                endpoint = "https://" + endpoint;
            }
        } else {
            endpoint = "https://oss-cn-beijing.aliyuncs.com";
        }
        return new OSSClientBuilder().build(
                endpoint,
                ossConfig.getAccessKeyId(),
                ossConfig.getAccessKeySecret());
    }
}
