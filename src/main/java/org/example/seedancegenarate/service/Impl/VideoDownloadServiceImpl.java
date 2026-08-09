package org.example.seedancegenarate.service.Impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.example.seedancegenarate.config.OssConfig;
import org.example.seedancegenarate.entity.VideoTask;
import org.example.seedancegenarate.mapper.VideoTaskMapper;
import org.example.seedancegenarate.service.ArtifactStorage;
import org.example.seedancegenarate.service.VideoDownloadService;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 提供方产物转存：远端响应流直接写 OSS，不再落到 API/Worker 实例的本地磁盘。
 * object key 按业务任务 ID 确定，Worker 重试可安全覆盖同一个对象。
 */
@Service
public class VideoDownloadServiceImpl extends ServiceImpl<VideoTaskMapper, VideoTask> implements VideoDownloadService {

    private final ArtifactStorage artifactStorage;
    private final OssConfig ossConfig;

    public VideoDownloadServiceImpl(ArtifactStorage artifactStorage, OssConfig ossConfig) {
        this.artifactStorage = artifactStorage;
        this.ossConfig = ossConfig;
    }

    @Override
    public DownloadedArtifact download(String remoteUrl, String bizTaskId) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(remoteUrl).openConnection();
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(120_000);
        connection.setRequestMethod("GET");
        connection.connect();
        int status = connection.getResponseCode();
        if (status < 200 || status >= 300) {
            connection.disconnect();
            throw new IllegalStateException("下载生成产物失败，HTTP 状态码: " + status);
        }

        try {
            String extension = resolveExtension(remoteUrl, connection.getContentType());
            String contentType = normalizeContentType(connection.getContentType(), extension);
            String objectKey = artifactObjectKey(bizTaskId, extension);
            try (InputStream input = connection.getInputStream()) {
                ArtifactStorage.StoredArtifact artifact = artifactStorage.put(
                        objectKey, input, contentType, connection.getContentLengthLong());
                return new DownloadedArtifact(bizTaskId + extension, artifact);
            }
        } finally {
            connection.disconnect();
        }
    }

    private String artifactObjectKey(String bizTaskId, String extension) {
        if (bizTaskId == null || bizTaskId.isBlank()) {
            throw new IllegalArgumentException("业务任务 ID 不能为空");
        }
        String prefix = ossConfig.getArtifactPrefix();
        String normalizedPrefix = prefix == null || prefix.isBlank() ? "outputs" : prefix.replaceAll("^/+|/+$", "");
        return normalizedPrefix + "/" + bizTaskId + "/result" + extension;
    }

    /** 优先取 ComfyUI filename 参数，再按路径 / 响应 Content-Type 推断真实扩展名。 */
    private String resolveExtension(String url, String contentType) {
        String candidate = extractFilenameParam(url);
        if (candidate == null) {
            int q = url.indexOf('?');
            candidate = q >= 0 ? url.substring(0, q) : url;
        }
        int dot = candidate.lastIndexOf('.');
        int slash = Math.max(candidate.lastIndexOf('/'), candidate.lastIndexOf('\\'));
        if (dot > slash) {
            String ext = candidate.substring(dot).toLowerCase(Locale.ROOT);
            if (ext.matches("\\.(mp4|webm|mov|mkv|gif|png|jpg|jpeg|webp|bmp)")) {
                return ext;
            }
        }
        String lowerContentType = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (lowerContentType.startsWith("image/png")) return ".png";
        if (lowerContentType.startsWith("image/jpeg")) return ".jpg";
        if (lowerContentType.startsWith("image/webp")) return ".webp";
        if (lowerContentType.startsWith("image/gif")) return ".gif";
        if (lowerContentType.startsWith("video/webm")) return ".webm";
        if (lowerContentType.startsWith("video/quicktime")) return ".mov";
        return ".mp4";
    }

    private String normalizeContentType(String contentType, String extension) {
        if (contentType != null && !contentType.isBlank()) {
            String normalized = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
            if (normalized.startsWith("video/") || normalized.startsWith("image/")) {
                return normalized;
            }
        }
        return switch (extension) {
            case ".png" -> "image/png";
            case ".jpg", ".jpeg" -> "image/jpeg";
            case ".webp" -> "image/webp";
            case ".gif" -> "image/gif";
            case ".bmp" -> "image/bmp";
            case ".webm" -> "video/webm";
            case ".mov" -> "video/quicktime";
            case ".mkv" -> "video/x-matroska";
            default -> "video/mp4";
        };
    }

    private String extractFilenameParam(String url) {
        int idx = url.indexOf("filename=");
        if (idx < 0) {
            return null;
        }
        int start = idx + "filename=".length();
        int end = url.indexOf('&', start);
        String raw = end >= 0 ? url.substring(start, end) : url.substring(start);
        return URLDecoder.decode(raw, StandardCharsets.UTF_8);
    }
}
