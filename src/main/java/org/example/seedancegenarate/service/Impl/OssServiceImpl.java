package org.example.seedancegenarate.service.Impl;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import lombok.RequiredArgsConstructor;
import org.example.seedancegenarate.config.OssConfig;
import org.example.seedancegenarate.service.OssService;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OssServiceImpl implements OssService {
    private final OssConfig ossConfig;
    public String upload(MultipartFile file) throws Exception {
        OSS ossClient = new OSSClientBuilder()
                .build(
                        ossConfig.getEndpoint(),
                        ossConfig.getAccessKeyId(),
                        ossConfig.getAccessKeySecret()
                );
        try {
            String filename = "images/" + UUID.randomUUID() + "_" + file.getOriginalFilename();
            ossClient.putObject(
                    ossConfig.getBucketName(),
                    filename,
                    file.getInputStream()
            );
            return resolveBaseDomain() + "/" + filename;
        } finally {
            ossClient.shutdown();
        }
    }

    @Override
    public String upload(byte[] bytes, String originalFilename) throws Exception {
        OSS ossClient = new OSSClientBuilder()
                .build(
                        ossConfig.getEndpoint(),
                        ossConfig.getAccessKeyId(),
                        ossConfig.getAccessKeySecret()
                );
        try {
            String safeName = originalFilename == null ? "api.png"
                    : originalFilename.replaceAll("[^a-zA-Z0-9._-]", "_");
            String filename = "images/" + UUID.randomUUID() + "_" + safeName;
            ossClient.putObject(ossConfig.getBucketName(), filename, new java.io.ByteArrayInputStream(bytes));
            return resolveBaseDomain() + "/" + filename;
        } finally {
            ossClient.shutdown();
        }
    }

    /**
     * 对象访问域名。优先用配置的 domain；未配置时回退为 https://{bucket}.{endpoint}（OSS 公网标准地址），
     * 避免返回缺少协议头的地址（会导致下游 http 客户端报 "Failed to select a proxy"）。
     */
    private String resolveBaseDomain() {
        String domain = ossConfig.getDomain();
        if (domain != null && !domain.isBlank()) {
            return domain.replaceAll("/+$", "");
        }
        String endpoint = ossConfig.getEndpoint() == null ? "" : ossConfig.getEndpoint().replaceFirst("^https?://", "");
        return "https://" + ossConfig.getBucketName() + "." + endpoint;
    }
}
