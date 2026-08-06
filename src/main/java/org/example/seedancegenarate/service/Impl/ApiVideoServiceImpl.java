package org.example.seedancegenarate.service.Impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.seedancegenarate.entity.ApiCallLog;
import org.example.seedancegenarate.entity.ApiKey;
import org.example.seedancegenarate.entity.VideoTask;
import org.example.seedancegenarate.engine.VideoEngine;
import org.example.seedancegenarate.engine.VideoEngineRegistry;
import org.example.seedancegenarate.exception.ApiException;
import org.example.seedancegenarate.mapper.ApiCallLogMapper;
import org.example.seedancegenarate.service.ApiVideoService;
import org.example.seedancegenarate.service.OssService;
import org.example.seedancegenarate.service.VideoSubmitService;
import org.example.seedancegenarate.service.VideoTaskService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * API 提交编排实现。幂等检查在图片副作用之前（重放请求不重复下载/生成/扣费）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApiVideoServiceImpl implements ApiVideoService {

    private final ApiCallLogMapper apiCallLogMapper;
    private final VideoSubmitService videoSubmitService;
    private final VideoEngineRegistry videoEngineRegistry;
    private final OssService ossService;
    private final VideoTaskService videoTaskService;

    @Override
    public VideoTask create(CreateContext context) {
        // 幂等快路径：同幂等键且已完成 → 直接返回原任务（同一钥匙；不重复生成、不重复扣费）
        ApiCallLog existing = apiCallLogMapper.selectOne(
                Wrappers.<ApiCallLog>lambdaQuery().eq(ApiCallLog::getRequestId, context.requestId()));
        if (existing != null && existing.getTaskId() != null
                && context.apiKey().getId().equals(existing.getApiKeyId())) {
            VideoTask task = videoTaskService.getOne(
                    Wrappers.<VideoTask>lambdaQuery().eq(VideoTask::getTaskId, existing.getTaskId()), false);
            if (task != null) {
                return task;
            }
        }

        // 模型定位（全局 id → 提供方）+ 开放闸门，均在图片副作用之前
        VideoEngine engine = resolveEngineForModel(context.model());
        videoSubmitService.validate(engine.provider(), context.model());

        // 图片 URL → 下载 → OSS（复用现有存储链路）
        List<String> imageUrls = uploadRemoteImages(context.imageUrls());

        long startMs = System.currentTimeMillis();
        ApiCallLog callLog = buildReceivedLog(context, imageUrls.size());
        try {
            apiCallLogMapper.insert(callLog);
        } catch (DuplicateKeyException e) {
            // 并发同幂等键：赢家可能刚插入尚未提交完
            ApiCallLog winner = apiCallLogMapper.selectOne(
                    Wrappers.<ApiCallLog>lambdaQuery().eq(ApiCallLog::getRequestId, context.requestId()));
            if (winner != null && winner.getTaskId() != null) {
                VideoTask task = videoTaskService.getOne(
                        Wrappers.<VideoTask>lambdaQuery().eq(VideoTask::getTaskId, winner.getTaskId()), false);
                if (task != null) {
                    return task;
                }
            }
            throw new ApiException("REQUEST_IN_PROGRESS", HttpStatus.CONFLICT,
                    "同一 Idempotency-Key 的请求正在处理中，请稍后查询");
        }

        try {
            VideoTask task = videoSubmitService.submit(new VideoSubmitService.SubmitRequest(
                    context.apiKey().getUserId(), engine.provider(), context.model(), context.prompt(),
                    imageUrls, context.duration(), context.ratio(), context.megapixels(),
                    context.apiKey().getId()));
            // 两阶段日志：回写 taskId + 排队耗时（终态由 ApiCallLogUpdater 收尾）
            ApiCallLog update = new ApiCallLog();
            update.setId(callLog.getId());
            update.setTaskId(task.getTaskId());
            update.setQueuedMs(System.currentTimeMillis() - startMs);
            apiCallLogMapper.updateById(update);
            return task;
        } catch (Exception e) {
            // 记录原始堆栈（错误码映射会丢失它）
            log.warn("API 提交失败: model={} requestId={}", context.model(), context.requestId(), e);
            markRejected(callLog, e);
            throw toApiException(e, context.model());
        }
    }

    /** 全局模型 id → 提供方引擎；找不到抛 400 */
    private VideoEngine resolveEngineForModel(String model) {
        String trimmed = model == null ? null : model.trim();
        if (!StringUtils.hasText(trimmed)) {
            throw ApiException.validation("model 不能为空");
        }
        for (VideoEngine engine : videoEngineRegistry.all()) {
            boolean known = engine.models().stream().anyMatch(spec -> spec.model().equals(trimmed));
            if (known) {
                return engine;
            }
        }
        throw ApiException.modelNotFound(trimmed);
    }

    /** 图片 URL 下载并转存 OSS；单个失败即整体失败（与 UI 传图失败语义一致） */
    private List<String> uploadRemoteImages(List<String> imageUrls) {
        List<String> urls = new ArrayList<>();
        if (imageUrls == null) {
            return urls;
        }
        for (String url : imageUrls) {
            if (!StringUtils.hasText(url)) {
                continue;
            }
            try {
                byte[] bytes = HttpUtil.downloadBytes(url.trim());
                if (bytes == null || bytes.length == 0) {
                    throw ApiException.validation("参考图下载失败: " + url);
                }
                String ext = StrUtil.subAfter(url.substring(0, Math.min(url.length(), 200)), ".", true);
                urls.add(ossService.upload(bytes, "api." + ext));
            } catch (ApiException e) {
                throw e;
            } catch (Exception e) {
                throw ApiException.validation("参考图下载失败: " + url + "（" + e.getMessage() + "）");
            }
        }
        return urls;
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

    /** 统一错误码：提交编排里的 RuntimeException 消息是我们自己的文案，按语义映射 */
    private String resolveErrorCode(Exception e) {
        String msg = e.getMessage() == null ? "" : e.getMessage();
        if (msg.contains("未开放")) return "MODEL_NOT_OPEN";
        if (msg.contains("不支持")) return "MODEL_NOT_FOUND";
        if (msg.contains("需要参考图") || msg.contains("数量需为") || msg.contains("不支持的比例")
                || msg.contains("必须指定 model")) return "VALIDATION_ERROR";
        if (msg.contains("ComfyUI") || msg.contains("节点")) return "PROVIDER_UNAVAILABLE";
        return "INTERNAL_ERROR";
    }

    private HttpStatus resolveHttpCode(Exception e) {
        return switch (resolveErrorCode(e)) {
            case "MODEL_NOT_OPEN" -> HttpStatus.FORBIDDEN;
            case "MODEL_NOT_FOUND", "VALIDATION_ERROR" -> HttpStatus.BAD_REQUEST;
            case "PROVIDER_UNAVAILABLE" -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }

    private ApiException toApiException(Exception e, String model) {
        String msg = e.getMessage() == null ? "" : e.getMessage();
        if (msg.contains("未开放")) {
            return ApiException.modelNotOpen();
        }
        if (msg.contains("不支持")) {
            return ApiException.modelNotFound(model);
        }
        if (msg.contains("需要参考图") || msg.contains("数量需为") || msg.contains("不支持的比例")) {
            return ApiException.validation(msg);
        }
        if (msg.contains("ComfyUI") || msg.contains("节点")) {
            return ApiException.providerUnavailable(msg);
        }
        if (e instanceof ApiException apiException) {
            return apiException;
        }
        return ApiException.internal(msg);
    }

    /** 供 controller 生成幂等键（无 Idempotency-Key 头时） */
    public static String generateRequestId() {
        return "req_" + UUID.randomUUID().toString().replace("-", "");
    }
}
