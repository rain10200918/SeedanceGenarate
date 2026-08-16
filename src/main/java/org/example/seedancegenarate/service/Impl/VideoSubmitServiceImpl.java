package org.example.seedancegenarate.service.Impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.seedancegenarate.config.VideoCompletionProperties;
import org.example.seedancegenarate.context.UserContext;
import org.example.seedancegenarate.engine.CompletionMechanism;
import org.example.seedancegenarate.engine.GenerateCommand;
import org.example.seedancegenarate.engine.GenerationMode;
import org.example.seedancegenarate.engine.OutputType;
import org.example.seedancegenarate.engine.SubmitResult;
import org.example.seedancegenarate.engine.VideoEngine;
import org.example.seedancegenarate.engine.VideoEngineRegistry;
import org.example.seedancegenarate.entity.VideoTask;
import org.example.seedancegenarate.event.TaskSubmittedEvent;
import org.example.seedancegenarate.service.CostRecordService;
import org.example.seedancegenarate.service.ModelAccessService;
import org.example.seedancegenarate.service.TaskStatusTransitioner;
import org.example.seedancegenarate.service.VideoSubmitService;
import org.example.seedancegenarate.service.VideoTaskService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * 提交编排实现。从 {@code VideoController} 提取（UI/API 共用）：
 * 解析实际生效模型 → 开放闸门 → 落库 → 引擎提交 → 回写 → 提交即计费。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VideoSubmitServiceImpl implements VideoSubmitService {

    private final VideoEngineRegistry videoEngineRegistry;
    private final VideoTaskService videoTaskService;
    private final CostRecordService costRecordService;
    private final ModelAccessService modelAccessService;
    private final TaskStatusTransitioner taskStatusTransitioner;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final VideoCompletionProperties completionProperties;

    /** 默认提供方；请求未显式指定 provider 时使用 */
    @Value("${video.default-provider:seedance}")
    private String defaultProvider;

    @Override
    public void validate(String provider, String model) {
        VideoEngine engine = videoEngineRegistry.get(resolveProvider(provider));
        assertModelOpen(engine.effectiveModel(model));
    }

    @Override
    public VideoTask submit(SubmitRequest request) throws Exception {
        String provider = resolveProvider(request.provider());
        VideoEngine engine = videoEngineRegistry.get(provider);
        // 闸门基于「实际生效的模型」而非请求原始值（防不传/乱传 model 绕过）
        String effectiveModel = engine.effectiveModel(request.model());
        assertModelOpen(effectiveModel);

        // 统一默认值（与 UI 控制器一致）：API 可能不传 duration/ratio，而 ComfyUI builder 的
        Integer duration = request.duration() == null ? 8 : request.duration();
        String ratio = (request.ratio() == null || request.ratio().isBlank()) ? "16:9" : request.ratio();

        // 任务类型 = (有无参考图) × (模型输出类型)
        List<String> imageUrls = request.imageUrls() == null ? Collections.emptyList() : request.imageUrls();
        List<String> videoUrls = request.videoUrls() == null ? Collections.emptyList() : request.videoUrls();
        List<String> audioUrls = request.audioUrls() == null ? Collections.emptyList() : request.audioUrls();
        boolean hasImages = !imageUrls.isEmpty();
        OutputType outputType = engine.outputType(effectiveModel);
        GenerationMode mode = GenerationMode.of(hasImages, outputType);

        // 业务 ID 在调用外部提供方之前生成：后续异步 Worker 即使尚未拿到 providerTaskId，
        // 也能立即对外返回稳定的任务标识。taskId 暂作为兼容别名，保持现有 UI/API 契约。
        String bizTaskId = "tsk_" + UUID.randomUUID().toString().replace("-", "");
        VideoTask task = new VideoTask();
        task.setUserId(request.userId());
        task.setBizTaskId(bizTaskId);
        task.setTaskId(bizTaskId);
        task.setPrompt(request.prompt());
        task.setImages(hasImages ? objectMapper.writeValueAsString(imageUrls) : null);
        task.setReferenceVideos(videoUrls.isEmpty() ? null : objectMapper.writeValueAsString(videoUrls));
        task.setReferenceAudios(audioUrls.isEmpty() ? null : objectMapper.writeValueAsString(audioUrls));
        task.setDuration(duration);
        task.setRatio(ratio);
        task.setStatus("PROCESSING");
        task.setProvider(provider);
        task.setModel(effectiveModel);
        task.setOutputType(outputType.name());
        task.setApiKeyId(request.apiKeyId());
        // 超时判定基准：本轮尝试起点（首次 = 创建时间）
        task.setLastAttemptAt(LocalDateTime.now());
        videoTaskService.save(task);

        GenerateCommand command = GenerateCommand.builder()
                .mode(mode)
                .imageUrls(imageUrls)
                .videoUrls(videoUrls)
                .audioUrls(audioUrls)
                .prompt(request.prompt())
                .duration(duration)
                .ratio(ratio)
                .model(effectiveModel)
                .megapixels(request.megapixels())
                .webhookUrl(resolveWebhookUrl(engine, provider))
                .build();
        log.info("提交生成任务: provider={}, model={}, taskId={}, webhookUrl={}",
                provider, effectiveModel, task.businessTaskId(),
                command.getWebhookUrl() == null ? "无（轮询推进）" : command.getWebhookUrl());
        SubmitResult submit;
        try {
            submit = engine.submit(command);
        } catch (Exception e) {
            // 提交失败：统一走终态唯一入口（CAS + 幂等 + SSE）。否则会留下「PROCESSING + 空
            // provider_task_id」的僵尸行，且 poller 只轮询已提交任务时永远不会碰它（清理不到）。
            taskStatusTransitioner.markFailed(task.getId(), e.getMessage());
            throw e;
        }
        task.setProviderTaskId(submit.getProviderTaskId());
        task.setNodeId(submit.getNodeId());
        videoTaskService.updateById(task);
        // 提交即计费仅对 ON_SUBMIT 提供方生效（如 Seedance），幂等。
        // 原由控制器 AOP 切面触发，现收进共享提交路径——UI 与对外 API 两条入口都走这里，都会计费。
        costRecordService.recordOnSubmit(task);
        // 任务提交成功事件：异步提交
        applicationEventPublisher.publishEvent(new TaskSubmittedEvent(request.userId(), task.businessTaskId(), imageUrls));
        return task;
    }

    /**
     * 超时自动重试：从已落库任务反推参数重新提交引擎（仅 ON_SUCCESS 计费引擎调用，
     * 免费重跑不产生费用；ON_SUBMIT 引擎的决策树已排除，不会走到这里）。
     * <p>
     * 并发安全：提交后 CAS 抢占 retry_count（{@code WHERE status='PROCESSING' AND retry_count=?}），
     * 多实例 Worker 竞争时只有一方回写成功，另一方返回 false 收工。
     *
     * @return true=本次执行了重提交；false=被其他实例抢先或任务已终态
     */
    public boolean resubmit(VideoTask task) throws Exception {
        if (task == null || task.getId() == null || !"PROCESSING".equals(task.getStatus())) {
            return false;
        }
        String provider = task.getProvider() == null || task.getProvider().isBlank()
                ? defaultProvider : task.getProvider().trim();
        VideoEngine engine = videoEngineRegistry.get(provider);
        String effectiveModel = task.getModel();
        // 重试时模型可能已被管理员关闭 → 不再重试，走失败
        assertModelOpen(effectiveModel);

        List<String> imageUrls = parseJsonList(task.getImages());
        List<String> videoUrls = parseJsonList(task.getReferenceVideos());
        List<String> audioUrls = parseJsonList(task.getReferenceAudios());
        boolean hasImages = !imageUrls.isEmpty();
        GenerationMode mode = GenerationMode.of(hasImages, engine.outputType(effectiveModel));

        GenerateCommand command = GenerateCommand.builder()
                .mode(mode)
                .imageUrls(imageUrls)
                .videoUrls(videoUrls)
                .audioUrls(audioUrls)
                .prompt(task.getPrompt())
                .duration(task.getDuration())
                .ratio(task.getRatio())
                .model(effectiveModel)
                .webhookUrl(resolveWebhookUrl(engine, provider))
                .build();
        int currentRetry = task.getRetryCount() == null ? 0 : task.getRetryCount();
        log.info("超时自动重试提交: taskId={}, provider={}, model={}, 第 {} 次重试",
                task.businessTaskId(), provider, effectiveModel, currentRetry + 1);
        SubmitResult submit = engine.submit(command);

        // CAS 抢占：仍 PROCESSING 且 retry_count 未被他人加过才回写；换新 provider_task_id 后
        // 旧 id 的迟到回调/轮询自然失效（按 provider_task_id 匹配查不到），无需额外清理。
        boolean updated = videoTaskService.update(new LambdaUpdateWrapper<VideoTask>()
                .eq(VideoTask::getId, task.getId())
                .eq(VideoTask::getStatus, "PROCESSING")
                .eq(VideoTask::getRetryCount, currentRetry)
                .set(VideoTask::getProviderTaskId, submit.getProviderTaskId())
                .set(VideoTask::getNodeId, submit.getNodeId())
                .set(VideoTask::getRetryCount, currentRetry + 1)
                .set(VideoTask::getLastAttemptAt, LocalDateTime.now())
                .set(VideoTask::getNextPollAt, null)); // NULL=立即可查，poller/对账立即接管新 id
        if (!updated) {
            log.warn("重试回写被抢先（任务已终态或已被重试），本次重试作废: taskId={}", task.businessTaskId());
            return false;
        }
        return true;
    }

    private List<String> parseJsonList(String json) {
        if (!StringUtils.hasText(json)) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(json, objectMapper.getTypeFactory()
                    .constructCollectionType(List.class, String.class));
        } catch (Exception e) {
            log.warn("解析任务参考素材失败（按空处理）: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private String resolveProvider(String provider) {
        return (provider == null || provider.isBlank()) ? defaultProvider : provider.trim();
    }

    /** 事件驱动引擎注入回调地址（带鉴权 token）；未配置基址或轮询引擎返回 null */
    private String resolveWebhookUrl(VideoEngine engine, String provider) {
        if (engine.completionMechanism() != CompletionMechanism.CALLBACK) {
            return null;
        }
        String base = completionProperties.getCallbackBaseUrl();
        String secret = completionProperties.getCallbackSecret();
        if (!StringUtils.hasText(base) || !StringUtils.hasText(secret)) {
            return null; // 未配置回调：引擎回退轮询兜底（对账任务）
        }
        return base.replaceAll("/+$", "")
                + "/api/callback/" + provider
                + "?token=" + java.net.URLEncoder.encode(secret, java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * 提交校验：普通用户不得使用未开放的模型（前端已过滤，此处后端硬拦，防手拼请求）。
     * 入参必须是 {@link VideoEngine#effectiveModel(String)} 解析后的实际生效模型。
     */
    private void assertModelOpen(String model) {
        if (!UserContext.isAdmin() && !modelAccessService.isOpen(model)) {
            throw new RuntimeException("该模型未开放");
        }
    }
}
