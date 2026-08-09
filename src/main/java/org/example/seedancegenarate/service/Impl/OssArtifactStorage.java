package org.example.seedancegenarate.service.Impl;

import com.aliyun.oss.OSS;
import com.aliyun.oss.model.GeneratePresignedUrlRequest;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.PutObjectResult;
import com.aliyun.oss.model.ResponseHeaderOverrides;
import lombok.RequiredArgsConstructor;
import org.example.seedancegenarate.config.OssConfig;
import org.example.seedancegenarate.service.ArtifactStorage;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.time.Duration;
import java.util.Date;

/** OSS 生成产物存储。OSS 对象是正式产物唯一真相，应用只在数据库保存 key。 */
@Service
@RequiredArgsConstructor
public class OssArtifactStorage implements ArtifactStorage {

    private final OSS ossClient;
    private final OssConfig ossConfig;

    @Override
    public StoredArtifact put(String objectKey, InputStream input, String contentType, long contentLength)
            throws Exception {
        ObjectMetadata metadata = new ObjectMetadata();
        if (contentType != null && !contentType.isBlank()) {
            metadata.setContentType(contentType);
        }
        if (contentLength >= 0) {
            metadata.setContentLength(contentLength);
        }
        PutObjectResult result = ossClient.putObject(ossConfig.getBucketName(), objectKey, input, metadata);
        return new StoredArtifact(objectKey, contentType, contentLength, result.getETag());
    }

    @Override
    public String createSignedGetUrl(String objectKey, Duration ttl) throws Exception {
        long seconds = ttl == null ? ossConfig.getSignedUrlTtlSeconds() : ttl.toSeconds();
        Date expiration = new Date(System.currentTimeMillis() + Math.max(seconds, 1) * 1000L);
        GeneratePresignedUrlRequest request = new GeneratePresignedUrlRequest(
                ossConfig.getBucketName(), objectKey);
        request.setExpiration(expiration);
        return ossClient.generatePresignedUrl(request).toString();
    }

    @Override
    public String createSignedDownloadUrl(String objectKey, String fileName, Duration ttl) throws Exception {
        long seconds = ttl == null ? ossConfig.getSignedUrlTtlSeconds() : ttl.toSeconds();
        Date expiration = new Date(System.currentTimeMillis() + Math.max(seconds, 1) * 1000L);
        GeneratePresignedUrlRequest request = new GeneratePresignedUrlRequest(
                ossConfig.getBucketName(), objectKey);
        request.setExpiration(expiration);
        ResponseHeaderOverrides headers = new ResponseHeaderOverrides();
        headers.setContentDisposition("attachment; filename=\"" + safeFileName(fileName) + "\"");
        request.setResponseHeaders(headers);
        return ossClient.generatePresignedUrl(request).toString();
    }

    @Override
    public boolean exists(String objectKey) throws Exception {
        return ossClient.doesObjectExist(ossConfig.getBucketName(), objectKey);
    }

    private String safeFileName(String fileName) {
        return (fileName == null ? "artifact" : fileName).replaceAll("[\\\\\"\\r\\n]", "_");
    }
}
