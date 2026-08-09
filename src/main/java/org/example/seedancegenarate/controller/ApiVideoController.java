package org.example.seedancegenarate.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.example.seedancegenarate.dto.ApiVideoCreateRequest;
import org.example.seedancegenarate.dto.ApiVideoCreateResponse;
import org.example.seedancegenarate.entity.ApiKey;
import org.example.seedancegenarate.entity.VideoTask;
import org.example.seedancegenarate.exception.ApiException;
import org.example.seedancegenarate.service.ApiDocService;
import org.example.seedancegenarate.service.ApiVideoService;
import org.example.seedancegenarate.service.Impl.ApiVideoServiceImpl;
import org.example.seedancegenarate.service.VideoTaskService;
import org.example.seedancegenarate.util.IpUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

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
        String requestId = (idempotencyKey != null && !idempotencyKey.isBlank())
                ? idempotencyKey.trim()
                : ApiVideoServiceImpl.generateRequestId();
        VideoTask task = apiVideoService.create(new ApiVideoService.CreateContext(
                key, requestId, IpUtils.getClientIp(servletRequest),
                servletRequest.getHeader("User-Agent"),
                request.prompt().trim(), request.model(), request.images(),
                request.duration(), request.ratio(), request.megapixels()));
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new ApiVideoCreateResponse(task.businessTaskId(), task.getStatus(), requestId));
    }

    /** 查状态：PROCESSING / SUCCESS（含结果）/ FAILED（含错误） */
    @GetMapping("/{taskId}")
    public VideoTask get(@PathVariable String taskId, HttpServletRequest servletRequest) {
        return findTask(currentKey(servletRequest).getId(), taskId);
    }

    /** 任务列表（该钥匙的，分页） */
    @GetMapping
    public Page<VideoTask> list(
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "10") long size,
            HttpServletRequest servletRequest
    ) {
        long pageCurrent = Math.max(current, 1L);
        long pageSize = Math.min(Math.max(size, 1L), 100L);
        return videoTaskService.page(
                new Page<>(pageCurrent, pageSize),
                Wrappers.<VideoTask>lambdaQuery()
                        .eq(VideoTask::getApiKeyId, currentKey(servletRequest).getId())
                        .orderByDesc(VideoTask::getId));
    }

    /** 下载产物（内联，按扩展名定 Content-Type） */
    @GetMapping("/{taskId}/content")
    public void content(@PathVariable String taskId, HttpServletRequest servletRequest,
                        HttpServletResponse response) throws Exception {
        VideoTask task = findTask(currentKey(servletRequest).getId(), taskId);
        if (task.getVideoUrl() == null || task.getVideoUrl().isBlank()) {
            throw ApiException.validation("任务尚无产物");
        }
        String stored = task.getVideoUrl();
        String fileName = stored.startsWith("data/videos/")
                ? stored.substring("data/videos/".length())
                : stored;
        Path path = Paths.get("data/videos/", fileName);
        if (!Files.exists(path)) {
            throw ApiException.validation("产物文件不存在");
        }
        response.setContentType(contentTypeOf(fileName));
        Files.copy(path, response.getOutputStream());
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

    /** 按扩展名推断 Content-Type（视频/图片通用），未知回退 video/mp4 */
    private String contentTypeOf(String fileName) {
        String lower = fileName.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webm")) return "video/webm";
        if (lower.endsWith(".mov")) return "video/quicktime";
        if (lower.endsWith(".mkv")) return "video/x-matroska";
        return "video/mp4";
    }
}
