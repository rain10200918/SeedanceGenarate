package org.example.seedancegenarate.service.Impl;

import com.aliyun.oss.OSS;
import cn.hutool.crypto.digest.DigestUtil;
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
    private final OSS ossClient;

    public OssServiceImpl(OssConfig ossConfig, OSS ossClient) {
        this.ossConfig = ossConfig;
        this.ossClient = ossClient;
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
     * 对象访问域名。配置可填完整 URL 或裸域名；裸域名统一补 https://，
     * 避免浏览器把 {@code bucket.endpoint/images/...} 误解释成相对路径。
     * 未配置时回退为 https://{bucket}.{endpoint}（OSS 公网标准地址）。
     */
    String resolveBaseDomain() {
        String domain = ossConfig.getDomain();
        if (domain != null && !domain.isBlank()) {
            String normalized = domain.trim().replaceAll("/+$", "");
            return normalized.matches("(?i)^https?://.*") ? normalized : "https://" + normalized;
        }
        String endpoint = ossConfig.getEndpoint();
        if (endpoint == null) {
            endpoint = "";
        } else {
            endpoint = endpoint.replaceFirst("(?i)^https?://", "");
        }
        return "https://" + ossConfig.getBucketName() + "." + endpoint;
    }
}