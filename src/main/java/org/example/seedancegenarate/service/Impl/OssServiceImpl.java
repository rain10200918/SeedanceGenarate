package org.example.seedancegenarate.service.Impl;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import cn.hutool.crypto.digest.DigestUtil;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.example.seedancegenarate.config.OssConfig;
import org.example.seedancegenarate.service.OssService;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.util.Locale;

/**
 * OSS 上传实现。
 * - 客户端单例复用：OSSClient 线程安全，不再每次上传 new + shutdown（连接池/线程反复重建的浪费）
 * - 文件名内容 hash 化：同名内容幂等落盘（重复上传同图不产生新对象），
 *   下游（ComfyUI input）按文件名去重，防止参考图目录无限增长
 */
@Slf4j
@Service
public class OssServiceImpl implements OssService {
    private final OssConfig ossConfig;
    private OSS ossClient;

    public OssServiceImpl(OssConfig ossConfig) {
        this.ossConfig = ossConfig;
    }

    @PostConstruct
    public void init() {
        ossClient = new OSSClientBuilder()
                .build(
                        ossConfig.getEndpoint(),
                        ossConfig.getAccessKeyId(),
                        ossConfig.getAccessKeySecret()
                );
    }

    @PreDestroy
    public void destroy() {
        if (ossClient != null) {
            ossClient.shutdown();
        }
    }

    @Override
    public String upload(MultipartFile file) throws Exception {
        byte[] bytes = file.getBytes();
        String filename = "images/" + DigestUtil.md5Hex(bytes) + safeExtension(file.getOriginalFilename());
        ossClient.putObject(ossConfig.getBucketName(), filename, new ByteArrayInputStream(bytes));
        return resolveBaseDomain() + "/" + filename;
    }

    @Override
    public String upload(byte[] bytes, String originalFilename) throws Exception {
        String filename = "images/" + DigestUtil.md5Hex(bytes) + safeExtension(originalFilename);
        ossClient.putObject(ossConfig.getBucketName(), filename, new ByteArrayInputStream(bytes));
        return resolveBaseDomain() + "/" + filename;
    }

    /** 提取安全的扩展名（含点，小写；识别不到返回空串，不附加） */
    private String safeExtension(String originalFilename) {
        if (originalFilename == null) {
            return "";
        }
        int dot = originalFilename.lastIndexOf('.');
        if (dot < 0) {
            return "";
        }
        return originalFilename.substring(dot).replaceAll("[^a-zA-Z0-9.]", "").toLowerCase(Locale.ROOT);
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
        String endpoint = ossConfig.getEndpoint();
        if (endpoint == null) {
            endpoint = "";
        } else {
            endpoint = endpoint.replaceFirst("^https?://", "");
        }
        return "https://" + ossConfig.getBucketName() + "." + endpoint;
    }
}