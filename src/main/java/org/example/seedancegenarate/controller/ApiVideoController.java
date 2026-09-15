package org.example.seedancegenarate.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.example.seedancegenarate.dto.ApiTaskView;
import org.example.seedancegenarate.dto.ApiTaskPageView;
import org.example.seedancegenarate.dto.ApiVideoCreateRequest;
import org.example.seedancegenarate.dto.ApiVideoCreateResponse;
import org.example.seedancegenarate.config.OssConfig;
import org.example.seedancegenarate.entity.ApiKey;
import org.example.seedancegenarate.entity.VideoTask;
import org.example.seedancegenarate.exception.ApiException;
import org.example.seedancegenarate.service.ApiDocService;
import org.example.seedancegenarate.service.ArtifactStorage;
import org.example.seedancegenarate.service.ApiVideoService;
import org.example.seedancegenarate.service.ContentModerationPolicy;
import org.example.seedancegenarate.service.Impl.ApiVideoServiceImpl;
import org.example.seedancegenarate.service.VideoTaskService;
import org.example.seedancegenarate.util.IpUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;

/**
 * 对外 API（/api/v1/**，ApiKeyInterceptor 鉴权）。
 * 契约见 API_SERVICE_DESIGN.md：提交异步 202 → 轮询 / webhook → 下载。
 */
@RestController
@RequestMapping("/api/v1/videos")
@RequiredArgsConstructor
public class ApiVideoController {
    private final ApiVideoService apiVideoService;
    private final VideoTaskService videoTaskService;
    private final ApiDocService apiDocService;
    private final ArtifactStorage artifactStorage;
    private final OssConfig ossConfig;
    private final org.example.seedancegenarate.service.ArtifactExpiryPolicy artifactExpiryPolicy;
    private final ContentModerationPolicy contentModerationPolicy;
    private Path localArtifactRoot = Paths.get("data/videos");

    /** 当前请求的 API Key（ApiKeyInterceptor 注入） */
    private static ApiKey currentKey(HttpServletRequest request) {
        Object key = request.getAttribute("api_key");
        if (!(key instanceof ApiKey apiKey)) {
            throw ApiException.invalidApiKey();
        }
        return apiKey;
    }

    /** 接入文档（原始 Markdown，供外部开发者查阅；管理页走 /api/admin/api-keys/docs） */
    @GetMapping("/docs")
    public String docs() {
        return apiDocService.content();
    }

    /** 提交生成任务（异步）：幂等键可选；202 返回 taskId，后续轮询/webhook 追踪 */
    @PostMapping
    public ResponseEntity<ApiVideoCreateResponse> create(
            @RequestBody ApiVideoCreateRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest servletRequest
    ) {
        ApiKey key = currentKey(servletRequest);
        if (request == null || request.prompt() == null || request.prompt().isBlank()) {
            throw ApiException.validation("prompt 不能为空");
        }
        String requestId;
        if (idempotencyKey == null) {
            requestId = ApiVideoServiceImpl.generateRequestId();
        } else {
            requestId = idempotencyKey.trim();
            // 原始头先查控制字符，不能让 trim 吞掉非法输入后变成另一个合法键。
            if (requestId.isBlank() || requestId.length() > 64
                    || idempotencyKey.codePoints().anyMatch(c -> Character.isISOControl(c)
                    || Character.getType(c) == Character.FORMAT || c == 0x2028 || c == 0x2029)) {
                throw ApiException.validation("Idempotency-Key 必须为1至64个字符，且不能包含控制字符");
            }
        }
        VideoTask task = apiVideoService.create(new ApiVideoService.CreateContext(
                key, requestId, IpUtils.getClientIp(servletRequest),
                servletRequest.getHeader("User-Agent"),
                request.prompt().trim(), request.model(), request.images(), request.videos(), request.audios(),
                request.duration(), request.ratio(), request.megapixels(), request.resolution()));
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new ApiVideoCreateResponse(task.businessTaskId(), task.getStatus(), requestId));
    }

    /** 查状态：PROCESSING / SUCCESS（含结果）/ FAILED（含错误） */
    @GetMapping("/{taskId}")
    public ApiTaskView get(@PathVariable String taskId, HttpServletRequest servletRequest) {
        return ApiTaskView.from(contentModerationPolicy.redact(
                artifactExpiryPolicy.stamp(findTask(currentKey(servletRequest).getId(), taskId))));
    }

    /** 任务列表（该钥匙的，分页） */
    @GetMapping
    public ApiTaskPageView list(
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "10") long size,
            HttpServletRequest servletRequest
    ) {
        long pageCurrent = Math.max(current, 1L);
        long pageSize = Math.min(Math.max(size, 1L), 100L);
        Page<VideoTask> page = artifactExpiryPolicy.stampAll(videoTaskService.page(
                new Page<>(pageCurrent, pageSize),
                Wrappers.<VideoTask>lambdaQuery()
                        .eq(VideoTask::getApiKeyId, currentKey(servletRequest).getId())
                        .orderByDesc(VideoTask::getId)));
        contentModerationPolicy.redactAll(page);
        return new ApiTaskPageView(page.getRecords().stream().map(ApiTaskView::from).toList(),
                page.getTotal(), page.getSize(), page.getCurrent(), page.getPages());
    }

    /** 下载产物（内联，按扩展名定 Content-Type） */
    @GetMapping("/{taskId}/content")
    public void content(@PathVariable String taskId, HttpServletRequest servletRequest,
                        HttpServletResponse response) throws Exception {
        VideoTask task = findTask(currentKey(servletRequest).getId(), taskId);
        if (contentModerationPolicy.isBlocked(task)) {
            throw ApiException.contentBlocked(contentModerationPolicy.blockedMessage(task));
        }
        if (task.getVideoUrl() == null || task.getVideoUrl().isBlank()) {
            throw ApiException.validation("任务尚无产物");
        }
        // 过期必须先判：createSignedGetUrl 是纯本地计算、不校验对象是否存在，
        // 放行下去只会 302 到一个必然 404 的地址，调用方拿不到任何可判断的信号。
        if (artifactExpiryPolicy.isExpired(task)) {
            throw ApiException.artifactExpired(artifactExpiryPolicy.expiredMessage());
        }
        if (hasOssArtifact(task)) {
            response.sendRedirect(artifactStorage.createSignedGetUrl(
                    task.getArtifactKey(), Duration.ofSeconds(ossConfig.getSignedUrlTtlSeconds())));
            return;
        }
        Path path = localArtifactPath(task.getVideoUrl());
        // 打开文件时也不跟随链接，避免校验后替换最终文件为符号链接。
        try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            response.setContentType(contentTypeOf(path.getFileName().toString()));
            input.transferTo(response.getOutputStream());
        }
    }

    private Path localArtifactPath(String stored) throws IOException {
        String fileName = stored.startsWith("data/videos/")
                ? stored.substring("data/videos/".length())
                : stored;
        if (fileName.length() > 255 || !fileName.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
            throw ApiException.validation("产物文件标识无效");
        }
        try {
            Path root = localArtifactRoot.toRealPath();
            Path candidate = root.resolve(fileName).normalize();
            if (!candidate.startsWith(root) || !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
                throw ApiException.validation("产物文件不可用");
            }
            Path real = candidate.toRealPath();
            if (!real.startsWith(root)) {
                throw ApiException.validation("产物文件不可用");
            }
            return candidate;
        } catch (NoSuchFileException e) {
            throw ApiException.validation("产物文件不存在");
        }
    }

    private boolean hasOssArtifact(VideoTask task) {
        return "OSS".equals(task.getArtifactStorageType())
                && task.getArtifactKey() != null && !task.getArtifactKey().isBlank();
    }

    private VideoTask findTask(Long apiKeyId, String taskId) {
        VideoTask task = videoTaskService.getOne(Wrappers.<VideoTask>lambdaQuery()
                .and(w -> w.eq(VideoTask::getBizTaskId, taskId)
                        .or()
                        .eq(VideoTask::getTaskId, taskId))
                .eq(VideoTask::getApiKeyId, apiKeyId), false);
        if (task == null) {
            throw ApiException.taskNotFound();
        }
        return task;
    }

    /** 按产物扩展名推断 MIME，未知类型不冒充可播放视频。 */
    private String contentTypeOf(String fileName) {
        String lower = fileName.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webm")) return "video/webm";
        if (lower.endsWith(".mov")) return "video/quicktime";
        if (lower.endsWith(".mkv")) return "video/x-matroska";
        if (lower.endsWith(".mp4")) return "video/mp4";
        if (lower.endsWith(".mp3")) return "audio/mpeg";
        if (lower.endsWith(".m4a")) return "audio/mp4";
        if (lower.endsWith(".aac")) return "audio/aac";
        if (lower.endsWith(".wav")) return "audio/wav";
        if (lower.endsWith(".ogg")) return "audio/ogg";
        if (lower.endsWith(".flac")) return "audio/flac";
        return "application/octet-stream";
    }
}
