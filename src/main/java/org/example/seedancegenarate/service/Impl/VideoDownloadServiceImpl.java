package org.example.seedancegenarate.service.Impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.example.seedancegenarate.config.OssConfig;
import org.example.seedancegenarate.engine.comfyui.ComfyUiProperties;
import org.example.seedancegenarate.entity.VideoTask;
import org.example.seedancegenarate.mapper.VideoTaskMapper;
import org.example.seedancegenarate.service.ArtifactStorage;
import org.example.seedancegenarate.service.VideoDownloadService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/**
 * 提供方产物转存：先落 Worker 临时文件再写 OSS，使 OSS SDK 失败重试时可重置输入流。
 * object key 按业务任务 ID + attempt 确定：同轮 Worker 重试可安全覆盖，不同轮永不互相覆盖。
 * 下载 ComfyUI /view 产物时携带 X-Comfy-Token（nginx 入口校验）。
 */
@Service
public class VideoDownloadServiceImpl extends ServiceImpl<VideoTaskMapper, VideoTask> implements VideoDownloadService {

    private final ArtifactStorage artifactStorage;
    private final OssConfig ossConfig;
    private final ComfyUiProperties comfyUiProperties;

    public VideoDownloadServiceImpl(ArtifactStorage artifactStorage, OssConfig ossConfig,
                                    ComfyUiProperties comfyUiProperties) {
        this.artifactStorage = artifactStorage;
        this.ossConfig = ossConfig;
        this.comfyUiProperties = comfyUiProperties;
    }

    @Override
    public DownloadedArtifact download(String remoteUrl, String bizTaskId, Long attemptId,
                                       String provider) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(remoteUrl).openConnection();
        try {
            // 绝不让携密请求自动跟随 3xx 到另一个主机；重定向由任务重试/人工排查处理。
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(120_000);
            connection.setRequestMethod("GET");
            String token = comfyUiProperties.getAccessToken();
            if (shouldAttachComfyToken(provider) && StringUtils.hasText(token)) {
                connection.setRequestProperty("X-Comfy-Token", token);
            }
            connection.connect();
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IllegalStateException("下载生成产物失败，HTTP 状态码: " + status);
            }
            String extension = resolveExtension(remoteUrl, connection.getContentType());
            String contentType = normalizeContentType(connection.getContentType(), extension);
            try (InputStream input = connection.getInputStream()) {
                return storeArtifact(input, bizTaskId, attemptId, extension, contentType);
            }
        } finally {
            connection.disconnect();
        }
    }

    /** copy 与 OSS put 任一阶段失败都删除临时文件；连接流的生命周期由调用方管理。 */
    DownloadedArtifact storeArtifact(InputStream input, String bizTaskId, Long attemptId,
                                     String extension, String contentType) throws Exception {
        String objectKey = artifactObjectKey(bizTaskId, attemptId, extension);
        // 先落临时文件再传 OSS：直传网络流在弱网下连接中断后 OSS SDK 重试要 reset 流，
        // HttpURLConnection 的流不支持 mark/reset → "Failed to reset the request input stream"。
        // 文件可 seek，重试无忧；大视频也不占内存。
        Path tempFile = createTempFile(extension);
        try {
            Files.copy(input, tempFile, StandardCopyOption.REPLACE_EXISTING);
            try (InputStream fileInput = Files.newInputStream(tempFile)) {
                ArtifactStorage.StoredArtifact artifact = artifactStorage.put(
                        objectKey, fileInput, contentType, Files.size(tempFile));
                return new DownloadedArtifact(bizTaskId + extension, artifact);
            }
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    Path createTempFile(String extension) throws Exception {
        return Files.createTempFile("dl-", extension);
    }

    boolean shouldAttachComfyToken(String provider) {
        return "comfyui".equalsIgnoreCase(provider == null ? "" : provider.trim());
    }

    String artifactObjectKey(String bizTaskId, Long attemptId, String extension) {
        if (bizTaskId == null || bizTaskId.isBlank()) {
            throw new IllegalArgumentException("业务任务 ID 不能为空");
        }
        String prefix = ossConfig.getArtifactPrefix();
        String normalizedPrefix = prefix == null || prefix.isBlank() ? "outputs" : prefix.replaceAll("^/+|/+$", "");
        String attemptSegment = attemptId == null ? "legacy" : attemptId.toString();
        return normalizedPrefix + "/" + bizTaskId + "/attempt-" + attemptSegment + "/result" + extension;
    }

    /** 优先取 ComfyUI filename 参数，再按路径 / 响应 Content-Type 推断真实扩展名。 */
    private String resolveExtension(String url, String contentType) {
        String path;
        try {
            path = URI.create(url).getPath();
        } catch (IllegalArgumentException e) {
            path = null;
        }
        for (String candidate : new String[]{extractFilenameParam(url), path}) {
            if (candidate == null) continue;
            int dot = candidate.lastIndexOf('.');
            int slash = Math.max(candidate.lastIndexOf('/'), candidate.lastIndexOf('\\'));
            if (dot > slash) {
                String ext = candidate.substring(dot).toLowerCase(Locale.ROOT);
                if (ext.matches("\\.(mp4|webm|mov|mkv|gif|png|jpg|jpeg|webp|bmp|mp3|m4a|aac|wav|ogg|flac)")) {
                    return ext;
                }
            }
        }
        String normalized = contentType == null ? "" : contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "image/png" -> ".png";
            case "image/jpeg" -> ".jpg";
            case "image/webp" -> ".webp";
            case "image/gif" -> ".gif";
            case "image/bmp" -> ".bmp";
            case "video/mp4" -> ".mp4";
            case "video/webm" -> ".webm";
            case "video/quicktime" -> ".mov";
            case "video/x-matroska" -> ".mkv";
            case "audio/mpeg", "audio/mp3" -> ".mp3";
            case "audio/mp4", "audio/x-m4a" -> ".m4a";
            case "audio/aac", "audio/x-aac" -> ".aac";
            case "audio/wav", "audio/x-wav", "audio/wave", "audio/vnd.wave" -> ".wav";
            case "audio/ogg", "application/ogg" -> ".ogg";
            case "audio/flac", "audio/x-flac" -> ".flac";
            default -> ".bin";
        };
    }

    private String normalizeContentType(String contentType, String extension) {
        if (contentType != null && !contentType.isBlank()) {
            String normalized = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
            if (normalized.startsWith("video/") || normalized.startsWith("image/")
                    || normalized.startsWith("audio/")) {
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
            case ".mp4" -> "video/mp4";
            case ".mp3" -> "audio/mpeg";
            case ".m4a" -> "audio/mp4";
            case ".aac" -> "audio/aac";
            case ".wav" -> "audio/wav";
            case ".ogg" -> "audio/ogg";
            case ".flac" -> "audio/flac";
            default -> "application/octet-stream";
        };
    }

    private String extractFilenameParam(String url) {
        try {
            String query = URI.create(url).getRawQuery();
            if (query == null) return null;
            for (String parameter : query.split("&")) {
                if (parameter.startsWith("filename=")) {
                    return URLDecoder.decode(parameter.substring("filename=".length()), StandardCharsets.UTF_8);
                }
            }
        } catch (IllegalArgumentException ignored) {
            // 无法解析的文件名不参与推断，继续使用路径或响应 MIME。
        }
        return null;
    }
}
