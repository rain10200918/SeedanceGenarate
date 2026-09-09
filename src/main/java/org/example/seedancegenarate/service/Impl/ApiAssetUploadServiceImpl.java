package org.example.seedancegenarate.service.Impl;

import com.aliyun.oss.OSS;
import com.aliyun.oss.model.PolicyConditions;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.seedancegenarate.config.OssConfig;
import org.example.seedancegenarate.dto.ApiAssetUploadUrlRequest;
import org.example.seedancegenarate.dto.ApiAssetUploadUrlResponse;
import org.example.seedancegenarate.exception.ApiException;
import org.example.seedancegenarate.service.ApiAssetUploadService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ApiAssetUploadServiceImpl implements ApiAssetUploadService {
    private static final long HARD_MAX_BYTES = 30L * 1024 * 1024;
    private static final long HARD_MAX_POLICY_TTL_SECONDS = 600L;
    private static final Set<String> CONTENT_TYPES = Set.of("image/jpeg", "image/png", "image/webp");

    private final OSS ossClient;
    private final OssConfig ossConfig;

    @Override
    public ApiAssetUploadUrlResponse issue(Long ownerId, ApiAssetUploadUrlRequest request) {
        if (ownerId == null) {
            throw ApiException.invalidApiKey();
        }
        validateRequest(request);
        validateConfig();

        String contentType = request.contentType().trim().toLowerCase(Locale.ROOT);
        String objectKey = normalizedPrefix() + "/" + ownerId + "/"
                + UUID.randomUUID().toString().replace("-", "") + extensionOf(contentType);
        Instant now = Instant.now();
        long policyTtl = Math.min(HARD_MAX_POLICY_TTL_SECONDS,
                Math.max(1L, ossConfig.getApiUploadPolicyTtlSeconds()));
        Instant uploadExpiresAt = now.plusSeconds(policyTtl);
        Instant assetExpiresAt = now.plusSeconds(Math.max(1L, ossConfig.getApiUploadReadTtlSeconds()));

        try {
            PolicyConditions conditions = new PolicyConditions();
            conditions.addConditionItem(PolicyConditions.COND_KEY, objectKey);
            conditions.addConditionItem(PolicyConditions.COND_CONTENT_TYPE, contentType);
            conditions.addConditionItem(PolicyConditions.COND_SUCCESS_ACTION_STATUS, "204");
            conditions.addConditionItem(PolicyConditions.COND_CONTENT_LENGTH_RANGE,
                    request.sizeBytes(), request.sizeBytes());
            String rawPolicy = ossClient.generatePostPolicy(Date.from(uploadExpiresAt), conditions);

            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("key", objectKey);
            fields.put("Content-Type", contentType);
            fields.put("success_action_status", "204");
            fields.put("policy", Base64.getEncoder().encodeToString(rawPolicy.getBytes(StandardCharsets.UTF_8)));
            fields.put("OSSAccessKeyId", ossConfig.getAccessKeyId().trim());
            fields.put("Signature", ossClient.calculatePostSignature(rawPolicy));

            String assetUrl = ossClient.generatePresignedUrl(
                    ossConfig.getBucketName().trim(), objectKey, Date.from(assetExpiresAt)).toString();
            return new ApiAssetUploadUrlResponse("POST", uploadOrigin(), Map.copyOf(fields),
                    ensureHttps(assetUrl), uploadExpiresAt, assetExpiresAt);
        } catch (Exception e) {
            log.warn("API 直传凭证签发失败: ownerId={}, err={}", ownerId, e.getMessage());
            throw ApiException.uploadCredentialUnavailable();
        }
    }

    private void validateRequest(ApiAssetUploadUrlRequest request) {
        if (request == null) {
            throw ApiException.validation("请求体不能为空");
        }
        if (!StringUtils.hasText(request.filename()) || request.filename().trim().length() > 255) {
            throw ApiException.validation("filename 不能为空且不能超过 255 个字符");
        }
        String contentType = request.contentType() == null
                ? "" : request.contentType().trim().toLowerCase(Locale.ROOT);
        if (!CONTENT_TYPES.contains(contentType)) {
            throw ApiException.validation("contentType 仅支持 image/jpeg、image/png 或 image/webp");
        }
        long maxBytes = Math.min(HARD_MAX_BYTES, Math.max(1L, ossConfig.getApiUploadMaxBytes()));
        if (request.sizeBytes() == null || request.sizeBytes() <= 0 || request.sizeBytes() > maxBytes) {
            throw ApiException.validation("sizeBytes 必须在 1 到 " + maxBytes + " 之间");
        }
    }

    private void validateConfig() {
        if (!StringUtils.hasText(ossConfig.getEndpoint())
                || !StringUtils.hasText(ossConfig.getAccessKeyId())
                || !StringUtils.hasText(ossConfig.getAccessKeySecret())
                || !StringUtils.hasText(ossConfig.getBucketName())) {
            throw ApiException.uploadCredentialUnavailable();
        }
    }

    private String normalizedPrefix() {
        String prefix = StringUtils.hasText(ossConfig.getApiUploadPrefix())
                ? ossConfig.getApiUploadPrefix().trim() : "api-uploads";
        prefix = prefix.replaceAll("^/+|/+$", "");
        return StringUtils.hasText(prefix) ? prefix : "api-uploads";
    }

    private String uploadOrigin() {
        String endpoint = ossConfig.getEndpoint().trim();
        if (!endpoint.matches("(?i)^https?://.*")) {
            endpoint = "https://" + endpoint;
        }
        URI uri = URI.create(endpoint);
        String host = uri.getHost();
        if (!StringUtils.hasText(host)) {
            throw new IllegalArgumentException("OSS endpoint 无效");
        }
        String bucket = ossConfig.getBucketName().trim();
        String uploadHost = host.equalsIgnoreCase(bucket) || host.toLowerCase(Locale.ROOT)
                .startsWith(bucket.toLowerCase(Locale.ROOT) + ".") ? host : bucket + "." + host;
        return "https://" + uploadHost + (uri.getPort() < 0 ? "" : ":" + uri.getPort());
    }

    private String extensionOf(String contentType) {
        return switch (contentType) {
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            default -> throw ApiException.validation("不支持的图片类型");
        };
    }

    private String ensureHttps(String url) {
        return url == null ? "" : url.replaceFirst("(?i)^http://", "https://");
    }
}
