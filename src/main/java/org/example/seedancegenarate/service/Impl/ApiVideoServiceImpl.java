package org.example.seedancegenarate.service.Impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.seedancegenarate.entity.ApiCallLog;
import org.example.seedancegenarate.entity.ApiKey;
import org.example.seedancegenarate.entity.VideoTask;
import org.example.seedancegenarate.engine.VideoEngine;
import org.example.seedancegenarate.engine.VideoEngineRegistry;
import org.example.seedancegenarate.exception.ApiException;
import org.example.seedancegenarate.exception.BusinessException;
import org.example.seedancegenarate.exception.ConcurrencyLimitExceededException;
import org.example.seedancegenarate.mapper.ApiCallLogMapper;
import org.example.seedancegenarate.service.ApiVideoService;
import org.example.seedancegenarate.service.ApiReferenceStorage;
import org.example.seedancegenarate.service.ApiRequestFingerprint;
import org.example.seedancegenarate.service.GenerationParameters;
import org.example.seedancegenarate.service.VideoSubmitService;
import org.example.seedancegenarate.service.VideoTaskService;
import org.example.seedancegenarate.util.IpUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * API 提交编排实现。幂等检查在参考媒体副作用之前（重放请求不重复下载/生成/扣费）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApiVideoServiceImpl implements ApiVideoService {

    private final ApiCallLogMapper apiCallLogMapper;
    private final VideoSubmitService videoSubmitService;
    private final VideoEngineRegistry videoEngineRegistry;
    private final ApiReferenceStorage referenceStorage;
    private final VideoTaskService videoTaskService;

    @Override
    public VideoSubmitService.PriceEstimate quote(String model, Integer duration) {
        if (duration != null && (duration < 1 || duration > 600)) {
            throw ApiException.validation("duration 必须在 1 到 600 之间");
        }
        String normalizedModel = normalizeModel(model);
        VideoEngine engine = resolveEngineForModel(normalizedModel);
        try {
            return videoSubmitService.estimate(engine.provider(), normalizedModel, duration);
        } catch (Exception e) {
            throw toApiException(e, normalizedModel);
        }
    }

    @Override
    public ModelTarget validateModel(String model) {
        String normalizedModel = normalizeModel(model);
        VideoEngine engine = resolveEngineForModel(normalizedModel);
        try {
            videoSubmitService.validate(engine.provider(), normalizedModel);
            return new ModelTarget(engine.provider(), normalizedModel);
        } catch (Exception e) {
            throw toApiException(e, normalizedModel);
        }
    }

    @Override
    public VideoTask create(CreateContext context) {
        if (!StringUtils.hasText(context.requestId()) || context.requestId().length() > 64) {
            throw ApiException.validation("Idempotency-Key 不能为空且不能超过 64 个字符");
        }
        String fingerprint = ApiRequestFingerprint.of(context);
        // 幂等快路径：日志可能已写 taskId，也可能旧实例死在「任务落库 → 日志补链」窗口。
        ApiCallLog existing = apiCallLogMapper.selectOne(
                Wrappers.<ApiCallLog>lambdaQuery().eq(ApiCallLog::getRequestId, context.requestId()));
        if (existing != null) {
            if (Objects.equals(context.apiKey().getId(), existing.getApiKeyId())) {
                assertSameRequest(existing, fingerprint);
                VideoTask recovered = recoverLoggedTask(context, existing, null);
                if (recovered != null) {
                    return recovered;
                }
            }
            // 已占用的幂等键绝不能继续下载参考媒体/重复提交；赢家尚未完成时让客户端稍后重放。
            throw requestInProgress();
        }

        // 模型定位（全局 id → 提供方）+ 开放闸门，均在参考媒体副作用之前
        VideoEngine engine = resolveEngineForModel(context.model());
        GenerationParameters parameters;
        String model = normalizeModel(context.model());
        try {
            videoSubmitService.validate(engine.provider(), model);
            if (!StringUtils.hasText(context.prompt()) || context.prompt().length() > 5000) {
                throw ApiException.validation("prompt 不能为空且不能超过 5000 个字符");
            }
            var modelSpec = engine.models().stream().filter(s -> s.model().equals(model)).findFirst()
                    .orElseThrow(() -> ApiException.modelNotFound(model));
            parameters = GenerationParameters.validate(modelSpec, context.duration(), context.ratio(),
                    context.megapixels(), size(context.imageUrls()), size(context.videoUrls()), size(context.audioUrls()));
        } catch (Exception e) {
            throw toApiException(e, context.model());
        }
        precheckReferences(context);

        // 仅本次请求拥有的对象可补偿；进入提交后先确认未受理，不能凭异常删素材。
        List<ApiReferenceStorage.OwnedReference> owned = new ArrayList<>();
        boolean submissionStarted = false;
        boolean accepted = false;
        boolean rejected = false;
        try {
            long[] downloadedBytes = {0L};
            List<String> imageUrls = uploadRemoteReferences(context.imageUrls(), ReferenceKind.IMAGE, owned, downloadedBytes);
            List<String> videoUrls = uploadRemoteReferences(context.videoUrls(), ReferenceKind.VIDEO, owned, downloadedBytes);
            List<String> audioUrls = uploadRemoteReferences(context.audioUrls(), ReferenceKind.AUDIO, owned, downloadedBytes);

            long startMs = System.currentTimeMillis();
            ApiCallLog callLog = buildReceivedLog(context, imageUrls.size());
            callLog.setRequestFingerprint(fingerprint);
            callLog.setDuration(parameters.duration());
            callLog.setRatio(parameters.ratio());
            try {
                apiCallLogMapper.insert(callLog);
            } catch (DuplicateKeyException e) {
                cleanupReferences(owned);
                // 并发同幂等键：赢家可能刚插入尚未提交完
                ApiCallLog winner = apiCallLogMapper.selectOne(
                        Wrappers.<ApiCallLog>lambdaQuery().eq(ApiCallLog::getRequestId, context.requestId()));
                if (winner != null && Objects.equals(context.apiKey().getId(), winner.getApiKeyId())) {
                    assertSameRequest(winner, fingerprint);
                    VideoTask recovered = recoverLoggedTask(context, winner,
                            System.currentTimeMillis() - startMs);
                    if (recovered != null) {
                        return recovered;
                    }
                }
                throw requestInProgress();
            }

            VideoTask task;
            try {
                submissionStarted = true;
                task = videoSubmitService.submit(new VideoSubmitService.SubmitRequest(
                        context.apiKey().getUserId(), engine.provider(), model, context.prompt().trim(),
                        imageUrls, videoUrls, audioUrls, parameters.duration(), parameters.ratio(), parameters.megapixels(),
                        context.apiKey().getId(), "api:" + context.requestId(), null));
                accepted = true;
            } catch (Exception e) {
                // 记录原始堆栈（错误码映射会丢失它）
                log.warn("API 提交失败: model={} requestId={}", context.model(), context.requestId(), e);
                rejected = definitelyNotAccepted(context);
                if (rejected) markRejected(callLog, e);
                throw toApiException(e, context.model());
            }

            try {
                // 先按 requestId 条件补 taskId，再重读任务终态。这样 Worker 无论在补链前还是补链后
                // 完成，ApiCallLogUpdater 与这里至少有一方能把 RECEIVED 收尾。
                return linkAndCatchUp(context, callLog, task, System.currentTimeMillis() - startMs);
            } catch (RuntimeException e) {
                // 任务已经受理，不能把日志伪装成 REJECTED。相同幂等键重放会再次按 requestId 补链。
                log.error("API 任务已受理但调用日志补链失败: requestId={}, taskId={}",
                        context.requestId(), task.businessTaskId(), e);
                throw ApiException.internal("任务已受理但状态关联暂时失败，请使用相同 Idempotency-Key 重试");
            }
        } finally {
            if (!accepted && (!submissionStarted || rejected)) {
                cleanupReferences(owned);
            }
        }
    }

    private boolean definitelyNotAccepted(CreateContext context) {
        try {
            return videoSubmitService.findByRequestId(context.apiKey().getUserId(),
                    "api:" + context.requestId()) == null;
        } catch (Exception e) {
            log.warn("API 受理状态不确定，保留本次素材: requestId={}", context.requestId());
            return false;
        }
    }

    private void assertSameRequest(ApiCallLog existing, String fingerprint) {
        // Legacy rows cannot be reconstructed from transformed OSS URLs. Do not fabricate a backfill.
        if (existing.getRequestFingerprint() != null && !existing.getRequestFingerprint().equals(fingerprint)) {
            throw new ApiException("IDEMPOTENCY_KEY_REUSED", HttpStatus.CONFLICT,
                    "同一 Idempotency-Key 已用于不同请求，请为新请求使用新的幂等键");
        }
    }

    private void cleanupReferences(List<ApiReferenceStorage.OwnedReference> owned) {
        for (var reference : owned) {
            try {
                referenceStorage.delete(reference);
            } catch (Exception e) {
                log.warn("API 素材补偿删除失败: objectKey={}", reference.objectKey());
            }
        }
        owned.clear();
    }

    /** 已有日志按 taskId 快查；taskId 尚未回写时，改用 video_task.request_id 追认。 */
    private VideoTask recoverLoggedTask(CreateContext context, ApiCallLog callLog, Long queuedMs) {
        VideoTask task;
        if (StringUtils.hasText(callLog.getTaskId())) {
            task = findByTaskId(callLog.getTaskId(), context.apiKey().getId());
        } else {
            task = videoSubmitService.findByRequestId(
                    context.apiKey().getUserId(), "api:" + context.requestId());
            // video_task 在 attempt/job 短事务之前已经可见；半成品随后可能被补偿删除，不能提前追认。
            if (!isDurablyQueued(task)) {
                return null;
            }
        }
        if (task == null || !Objects.equals(context.apiKey().getId(), task.getApiKeyId())) {
            return null;
        }
        return linkAndCatchUp(context, callLog, task, queuedMs);
    }

    private VideoTask linkAndCatchUp(CreateContext context, ApiCallLog callLog,
                                     VideoTask task, Long queuedMs) {
        String taskId = task.businessTaskId();
        if (!StringUtils.hasText(taskId)) {
            throw new IllegalStateException("生成任务缺少业务 taskId");
        }
        if (!StringUtils.hasText(callLog.getTaskId())) {
            Long normalizedQueuedMs = queuedMs == null ? null : Math.max(0L, queuedMs);
            int changed = apiCallLogMapper.linkTaskByRequestId(
                    callLog.getId(), context.apiKey().getId(), context.requestId(), taskId, normalizedQueuedMs);
            if (changed != 1) {
                ApiCallLog latestLog = apiCallLogMapper.selectOne(
                        Wrappers.<ApiCallLog>lambdaQuery()
                                .eq(ApiCallLog::getRequestId, context.requestId())
                                .eq(ApiCallLog::getApiKeyId, context.apiKey().getId()));
                if (latestLog == null || !taskId.equals(latestLog.getTaskId())) {
                    throw new IllegalStateException("API 调用日志 taskId 补链失败");
                }
                callLog = latestLog;
            } else {
                callLog.setTaskId(taskId);
                if (normalizedQueuedMs != null) {
                    callLog.setQueuedMs(normalizedQueuedMs);
                }
            }
        }

        // 补链完成后重读：若快速 Worker 已经先发完终态事件，就在这里补收尾；若尚未终态，
        // 后续事件会按刚落下的 taskId 正常命中。两种时序都不会永久停在 RECEIVED。
        VideoTask latestTask = findByTaskId(taskId, context.apiKey().getId());
        if (latestTask == null) {
            latestTask = task;
        }
        catchUpTerminalLog(callLog, latestTask);
        return latestTask;
    }

    private void catchUpTerminalLog(ApiCallLog callLog, VideoTask task) {
        if (!("SUCCESS".equals(task.getStatus()) || "FAILED".equals(task.getStatus()))) {
            return;
        }
        Long totalMs = null;
        LocalDateTime start = callLog.getCreateTime();
        if (start != null) {
            totalMs = Math.max(0L, Duration.between(start, LocalDateTime.now()).toMillis());
        }
        apiCallLogMapper.finishReceived(callLog.getId(), task.businessTaskId(), task.getStatus(),
                StringUtils.hasText(task.getErrorMsg()) ? task.getErrorMsg() : null,
                task.getCostAmount(), totalMs);
    }

    private boolean isDurablyQueued(VideoTask task) {
        if (task == null || !StringUtils.hasText(task.businessTaskId())) {
            return false;
        }
        if (!"PROCESSING".equals(task.getStatus())) {
            return true;
        }
        return task.getCurrentAttemptId() != null
                || StringUtils.hasText(task.getPhase())
                || StringUtils.hasText(task.getProviderTaskId());
    }

    private ApiException requestInProgress() {
        return new ApiException("REQUEST_IN_PROGRESS", HttpStatus.CONFLICT,
                "同一 Idempotency-Key 的请求正在处理中，请稍后查询");
    }

    /** 全局模型 id → 提供方引擎；找不到抛 400 */
    private VideoEngine resolveEngineForModel(String model) {
        String trimmed = normalizeModel(model);
        for (VideoEngine engine : videoEngineRegistry.all()) {
            boolean known = engine.models().stream().anyMatch(spec -> spec.model().equals(trimmed));
            if (known) {
                return engine;
            }
        }
        throw ApiException.modelNotFound(trimmed);
    }

    private String normalizeModel(String model) {
        String trimmed = model == null ? null : model.trim();
        if (!StringUtils.hasText(trimmed)) {
            throw ApiException.validation("model 不能为空");
        }
        return trimmed;
    }

    /** 按新业务 ID 查询，同时兼容迁移前的 legacy task_id。 */
    private VideoTask findByTaskId(String taskId, Long apiKeyId) {
        return videoTaskService.getOne(Wrappers.<VideoTask>lambdaQuery()
                .and(w -> w.eq(VideoTask::getBizTaskId, taskId)
                        .or()
                        .eq(VideoTask::getTaskId, taskId))
                .eq(VideoTask::getApiKeyId, apiKeyId), false);
    }

    private static final int MAX_REFERENCE_SIZE_BYTES = 30 * 1024 * 1024; // 30MB per reference
    private static final long MAX_TOTAL_REFERENCE_BYTES = 100L * 1024 * 1024;
    private static final int DOWNLOAD_TIMEOUT_MS = 5000; // 5s
    private static final int MAX_REDIRECTS = 3;

    private enum ReferenceKind {
        IMAGE("参考图", "image/", ".png"),
        VIDEO("参考视频", "video/", ".mp4"),
        AUDIO("参考音频", "audio/", ".mp3");

        private final String label;
        private final String contentTypePrefix;
        private final String defaultExtension;

        ReferenceKind(String label, String contentTypePrefix, String defaultExtension) {
            this.label = label;
            this.contentTypePrefix = contentTypePrefix;
            this.defaultExtension = defaultExtension;
        }
    }

    private record DownloadedReference(byte[] bytes, String extension) {
    }

    /** 参考媒体 URL 下载并转存 OSS；单个失败即整体失败（与 UI 传素材失败语义一致） */
    private void precheckReferences(CreateContext context) {
        long count = (long) size(context.imageUrls()) + size(context.videoUrls()) + size(context.audioUrls());
        if (count > 16) throw ApiException.validation("参考媒体总数不能超过 16");
        precheckUrls(context.imageUrls(), ReferenceKind.IMAGE);
        precheckUrls(context.videoUrls(), ReferenceKind.VIDEO);
        precheckUrls(context.audioUrls(), ReferenceKind.AUDIO);
    }

    private static int size(List<?> values) { return values == null ? 0 : values.size(); }

    private void precheckUrls(List<String> urls, ReferenceKind kind) {
        if (urls == null) return;
        for (String url : urls) {
            if (!StringUtils.hasText(url) || url.length() > 4096) {
                throw ApiException.validation(kind.label + "地址不能为空且不能超过 4096 个字符");
            }
            try {
                validateRemoteReferenceUri(new URI(url.trim()), kind, "[参考媒体]");
            } catch (URISyntaxException e) {
                throw ApiException.validation(kind.label + "地址格式不合法");
            }
        }
    }

    private List<String> uploadRemoteReferences(List<String> referenceUrls, ReferenceKind kind,
            List<ApiReferenceStorage.OwnedReference> owned, long[] downloadedBytes) {
        List<String> urls = new ArrayList<>();
        if (referenceUrls == null) {
            return urls;
        }
        for (String url : referenceUrls) {
            DownloadedReference downloaded = downloadSafeReference(url.trim(), kind, downloadedBytes);
            try {
                var reference = referenceStorage.upload(downloaded.bytes(), downloaded.extension());
                owned.add(reference);
                urls.add(reference.url());
            } catch (Exception e) {
                log.warn("转存{}到 OSS 失败", kind.label);
                throw ApiException.internal("转存" + kind.label + "失败");
            }
        }
        return urls;
    }

    /**
     * 安全下载外部参考媒体：
     * 1. 协议限制：仅允许 http/https
     * 2. SSRF 防护：每次重定向都禁止内网、本地、回环、链路本地 IP
     * 3. 资源保护：设置 5 秒连接/读取超时，单个素材限制最大 30MB，防止 OOM 与慢连接耗尽线程
     */
    private DownloadedReference downloadSafeReference(String url, ReferenceKind kind, long[] downloadedBytes) {
        URI current;
        try {
            current = new URI(url.trim());
        } catch (URISyntaxException e) {
            throw ApiException.validation(kind.label + "地址格式不合法");
        }
        for (int redirect = 0; redirect <= MAX_REDIRECTS; redirect++) {
            validateRemoteReferenceUri(current, kind, "[参考媒体]");
            HttpURLConnection conn = null;
            try {
                conn = openReferenceConnection(current);
                conn.setConnectTimeout(DOWNLOAD_TIMEOUT_MS);
                conn.setReadTimeout(DOWNLOAD_TIMEOUT_MS);
                conn.setInstanceFollowRedirects(false);
                conn.setRequestProperty("User-Agent", "SeedanceApi/1.0");

                int responseCode = conn.getResponseCode();
                if (responseCode >= 300 && responseCode < 400) {
                    if (redirect == MAX_REDIRECTS) {
                        throw ApiException.validation(kind.label + "重定向次数超过限制");
                    }
                    String location = conn.getHeaderField("Location");
                    if (!StringUtils.hasText(location)) {
                        throw ApiException.validation(kind.label + "重定向缺少目标地址");
                    }
                    try {
                        current = current.resolve(location.trim());
                    } catch (IllegalArgumentException e) {
                        throw ApiException.validation(kind.label + "重定向地址格式不合法");
                    }
                    continue;
                }
                if (responseCode < 200 || responseCode >= 300) {
                    throw ApiException.validation(kind.label + "下载响应异常 (HTTP " + responseCode + ")");
                }

                long contentLength = conn.getContentLengthLong();
                if (contentLength > MAX_REFERENCE_SIZE_BYTES) {
                    throw ApiException.validation(kind.label + "大小超过限制 (最大 30MB)");
                }
                if (contentLength > MAX_TOTAL_REFERENCE_BYTES - downloadedBytes[0]) {
                    throw ApiException.validation("参考媒体总大小超过限制 (最大 100MiB)");
                }

                String contentType = conn.getContentType();
                if (StringUtils.hasText(contentType)) {
                    String normalized = contentType.toLowerCase(Locale.ROOT);
                    if (!normalized.startsWith(kind.contentTypePrefix)
                            && !normalized.startsWith("application/octet-stream")) {
                        throw ApiException.validation(kind.label + "类型不匹配");
                    }
                }

                try (InputStream in = conn.getInputStream();
                     ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192];
                    int total = 0;
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        total += read;
                        downloadedBytes[0] += read;
                        if (downloadedBytes[0] > MAX_TOTAL_REFERENCE_BYTES) {
                            throw ApiException.validation("参考媒体总大小超过限制 (最大 100MiB)");
                        }
                        if (total > MAX_REFERENCE_SIZE_BYTES) {
                            throw ApiException.validation(kind.label + "大小超过限制 (最大 30MB)");
                        }
                        out.write(buffer, 0, read);
                    }
                    byte[] bytes = out.toByteArray();
                    if (bytes.length == 0) {
                        throw ApiException.validation(kind.label + "内容为空");
                    }
                    return new DownloadedReference(bytes, extensionFor(current, contentType, kind));
                }
            } catch (ApiException e) {
                throw e;
            } catch (Exception e) {
                log.warn("下载外部{}失败", kind.label);
                throw ApiException.validation(kind.label + "下载失败");
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }
        throw ApiException.validation(kind.label + "下载失败");
    }

    // 包级测试接缝：测试使用假连接，不访问真实外网；生产仍逐跳执行 URI/DNS 校验。
    HttpURLConnection openReferenceConnection(URI uri) throws java.io.IOException {
        return (HttpURLConnection) uri.toURL().openConnection();
    }

    private void validateRemoteReferenceUri(URI uri, ReferenceKind kind, String originalUrl) {
        if (uri.getPort() < -1 || uri.getPort() == 0 || uri.getPort() > 65535) {
            throw ApiException.validation(kind.label + "地址端口无效");
        }
        if (uri.getUserInfo() != null) {
            throw ApiException.validation(kind.label + "地址不能包含用户名密码");
        }
        String scheme = uri.getScheme();
        if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
            throw ApiException.validation(kind.label + "地址必须为 http 或 https 协议: " + originalUrl);
        }
        String host = uri.getHost();
        if (!StringUtils.hasText(host)) {
            throw ApiException.validation(kind.label + "地址缺少主机名: " + originalUrl);
        }
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            for (InetAddress addr : addresses) {
                if (IpUtils.isPrivateOrLocalAddress(addr)) {
                    log.warn("拒绝访问内网{}地址 (SSRF 防护): url={}, ip={}", kind.label, originalUrl,
                            addr.getHostAddress());
                    throw ApiException.validation("禁止使用内网或本地" + kind.label + "地址");
                }
            }
        } catch (UnknownHostException e) {
            throw ApiException.validation("无法解析" + kind.label + "域名: " + host);
        }
    }

    private String extensionFor(URI uri, String contentType, ReferenceKind kind) {
        String path = uri.getPath();
        if (path != null) {
            int dot = path.lastIndexOf('.');
            int slash = path.lastIndexOf('/');
            if (dot > slash) {
                String candidate = path.substring(dot).toLowerCase(Locale.ROOT);
                if (isAllowedExtension(candidate, kind)) {
                    return ".jpeg".equals(candidate) ? ".jpg" : candidate;
                }
            }
        }
        String normalizedType = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (normalizedType.startsWith("image/jpeg")) return ".jpg";
        if (normalizedType.startsWith("image/png")) return ".png";
        if (normalizedType.startsWith("image/webp")) return ".webp";
        if (normalizedType.startsWith("image/gif")) return ".gif";
        if (normalizedType.startsWith("video/webm")) return ".webm";
        if (normalizedType.startsWith("video/quicktime")) return ".mov";
        if (normalizedType.startsWith("audio/wav") || normalizedType.startsWith("audio/x-wav")) return ".wav";
        if (normalizedType.startsWith("audio/mpeg") || normalizedType.startsWith("audio/mp3")) return ".mp3";
        if (normalizedType.startsWith("audio/mp4")) return ".m4a";
        if (normalizedType.startsWith("audio/ogg")) return ".ogg";
        return kind.defaultExtension;
    }

    private boolean isAllowedExtension(String extension, ReferenceKind kind) {
        return switch (kind) {
            case IMAGE -> List.of(".jpg", ".jpeg", ".png", ".webp", ".gif").contains(extension);
            case VIDEO -> List.of(".mp4", ".webm", ".mov", ".mkv").contains(extension);
            case AUDIO -> List.of(".mp3", ".wav", ".m4a", ".aac", ".ogg", ".flac", ".webm").contains(extension);
        };
    }

    private ApiCallLog buildReceivedLog(CreateContext context, int imageCount) {
        ApiCallLog log = new ApiCallLog();
        log.setRequestId(context.requestId());
        log.setApiKeyId(context.apiKey().getId());
        log.setUserId(context.apiKey().getUserId());
        log.setEndpoint("POST /api/v1/videos");
        log.setMethod("POST");
        log.setModel(context.model() == null ? null : context.model().trim());
        log.setImageCount(imageCount);
        log.setDuration(context.duration());
        log.setRatio(context.ratio());
        log.setMegapixels(context.megapixels());
        log.setStatus("RECEIVED");
        log.setClientIp(context.clientIp());
        log.setUserAgent(context.userAgent());
        return log;
    }

    /** 提交失败 → REJECTED 日志（含错误码，供统计拒绝分布） */
    private void markRejected(ApiCallLog callLog, Exception e) {
        try {
            ApiCallLog update = new ApiCallLog();
            update.setId(callLog.getId());
            update.setStatus("REJECTED");
            update.setErrorMsg(StrUtil.maxLength(e.getMessage(), 500));
            update.setErrorCode(resolveErrorCode(e));
            update.setHttpCode(resolveHttpCode(e).value());
            apiCallLogMapper.updateById(update);
        } catch (Exception logError) {
            log.warn("写入 API 调用日志失败: {}", logError.getMessage());
        }
    }

    /** 响应和调用日志使用相同的类型优先分类。 */
    private String resolveErrorCode(Exception e) {
        return org.example.seedancegenarate.exception.ApiFailureClassifier.classify(e).getCode();
    }

    private HttpStatus resolveHttpCode(Exception e) {
        return org.example.seedancegenarate.exception.ApiFailureClassifier.classify(e).getHttpStatus();
    }

    private ApiException toApiException(Exception e, String model) {
        return org.example.seedancegenarate.exception.ApiFailureClassifier.classify(e);
    }

    /** 供 controller 生成幂等键（无 Idempotency-Key 头时） */
    public static String generateRequestId() {
        return "req_" + UUID.randomUUID().toString().replace("-", "");
    }
}
