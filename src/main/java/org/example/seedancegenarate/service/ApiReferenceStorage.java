package org.example.seedancegenarate.service;

import com.aliyun.oss.OSS;
import org.example.seedancegenarate.config.OssConfig;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** API 请求独占的参考素材；由调用方决定何时可以补偿删除。 */
@Component
public class ApiReferenceStorage {
    private static final String PREFIX = "api-references/";
    private static final Pattern OWNED_KEY = Pattern.compile(
            "api-references/[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\\.[a-z0-9]+");

    private final OssConfig config;
    private final OSS ossClient;

    public ApiReferenceStorage(OssConfig config, OSS ossClient) {
        this.config = Objects.requireNonNull(config, "config");
        this.ossClient = Objects.requireNonNull(ossClient, "ossClient");
    }

    public OwnedReference upload(byte[] bytes, String extension) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("Reference bytes must not be empty");
        }
        if (extension == null || !extension.matches("\\.?[a-zA-Z0-9]+")) {
            throw new IllegalArgumentException("Invalid reference extension");
        }
        String suffix = extension.startsWith(".") ? extension.substring(1) : extension;
        String key = PREFIX + UUID.randomUUID() + "." + suffix.toLowerCase(Locale.ROOT);
        OwnedReference owned = new OwnedReference(resolveBaseDomain() + "/" + key, key);
        String bucket = config.getBucketName();
        try {
            ossClient.putObject(bucket, key, new ByteArrayInputStream(bytes));
        } catch (RuntimeException failure) {
            try {
                ossClient.deleteObject(bucket, key);
            } catch (RuntimeException cleanupFailure) {
                // 同一个异常实例不能自我 suppressed，仍须保留原上传错误。
                if (cleanupFailure != failure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            throw failure;
        }
        return owned;
    }

    public void delete(OwnedReference reference) {
        if (reference == null || reference.objectKey() == null
                || !OWNED_KEY.matcher(reference.objectKey()).matches()) {
            throw new IllegalArgumentException("Not an API-owned reference key");
        }
        ossClient.deleteObject(config.getBucketName(), reference.objectKey());
    }

    private String resolveBaseDomain() {
        String domain = config.getDomain();
        if (domain != null && !domain.isBlank()) {
            String normalized = domain.trim().replaceAll("/+$", "");
            return normalized.matches("(?i)^https?://.*") ? normalized : "https://" + normalized;
        }
        String endpoint = config.getEndpoint();
        endpoint = endpoint == null ? "" : endpoint.replaceFirst("(?i)^https?://", "");
        return "https://" + config.getBucketName() + "." + endpoint;
    }

    public static final class OwnedReference {
        private final String url;
        private final String objectKey;

        private OwnedReference(String url, String objectKey) {
            this.url = url;
            this.objectKey = objectKey;
        }

        public String url() {
            return url;
        }

        public String objectKey() {
            return objectKey;
        }
    }
}
