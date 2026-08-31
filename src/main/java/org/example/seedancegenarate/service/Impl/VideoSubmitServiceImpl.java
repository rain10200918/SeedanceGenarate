package org.example.seedancegenarate.service.Impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
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
import org.example.seedancegenarate.entity.ApiKey;
import org.example.seedancegenarate.entity.VideoTask;
import org.example.seedancegenarate.exception.ConcurrencyLimitExceededException;
import org.example.seedancegenarate.exception.BusinessException;
import org.example.seedancegenarate.mapper.ApiKeyMapper;
import org.example.seedancegenarate.mapper.AppUserMapper;
import org.example.seedancegenarate.service.AdmissionControl;
import org.example.seedancegenarate.service.AdmissionResult;
import org.example.seedancegenarate.service.ConcurrencyLimit;
import org.example.seedancegenarate.service.ConcurrencyPolicy;
import org.example.seedancegenarate.event.TaskSubmittedEvent;
import org.example.seedancegenarate.service.ModelAccessService;
import org.example.seedancegenarate.service.PricingService;
import org.example.seedancegenarate.service.TaskStatusTransitioner;
import org.example.seedancegenarate.service.VideoSubmitService;
import org.example.seedancegenarate.service.VideoTaskService;
import org.example.seedancegenarate.service.WalletService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
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
    private final ModelAccessService modelAccessService;
    private final TaskStatusTransitioner taskStatusTransitioner;
    private final WalletService walletService;
    private final PricingService pricingService;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final VideoCompletionProperties completionProperties;
    private final AppUserMapper appUserMapper;
    private final ApiKeyMapper apiKeyMapper;
    private final ConcurrencyPolicy concurrencyPolicy;
    private final AdmissionControl admissionControl;

    /** 默认提供方；请求未显式指定 provider 时使用 */
    @Value("${video.default-provider:seedance}")
    private String defaultProvider;

    @Override
    public VideoTask findByRequestId(Long userId, String requestId) {
        if (userId == null || !StringUtils.hasText(requestId)) {
            return null;
        }
        return videoTaskService.getOne(Wrappers.<VideoTask>lambdaQuery()
                .eq(VideoTask::getUserId, userId)
                .eq(VideoTask::getRequestId, requestId.trim())
                .last("limit 1"), false);
    }

    @Override
    public void validate(String provider, String model) {
        VideoEngine engine = videoEngineRegistry.get(resolveProvider(provider));
        assertModelOpen(engine.effectiveModel(model));
    }

    @Override
    public void validatePinnedNode(String provider, String nodeId) {
        if (!StringUtils.hasText(nodeId)) {
            return;
        }
        if (!UserContext.isAdmin()) {
            throw BusinessException.forbidden("指定 ComfyUI 节点仅限管理员灰度验证");
        }
        String normalized = nodeId.trim();
        if (normalized.length() > 64) {
            throw BusinessException.badRequest("节点 id 不能超过 64 个字符");
        }
        if (!"comfyui".equalsIgnoreCase(resolveProvider(provider))) {
            throw BusinessException.badRequest("指定节点只适用于 ComfyUI 提供方");
        }
    }

    @Override
    public PriceEstimate estimate(String provider, String model, Integer duration) {
        ResolvedSpec spec = resolveSpec(provider, model, duration);
        // 探针任务只为复用 price() 的字段口径，不落库、无任何副作用
        VideoTask probe = new VideoTask();
        probe.setProvider(spec.provider());
        probe.setModel(spec.effectiveModel());
        probe.setDuration(spec.duration());
        probe.setOutputType(spec.outputType().name());
        PricingService.Price price = pricingService.price(probe);
        return new PriceEstimate(spec.provider(), spec.effectiveModel(), spec.duration(),
                spec.outputType().name(), price.unitPrice(), price.amount(), price.currency());
    }

    @Override
    public VideoTask submit(SubmitRequest request) throws Exception {
        validatePinnedNode(request.provider(), request.nodeId());
        ResolvedSpec spec = resolveSpec(request.provider(), request.model(), request.duration());
        String provider = spec.provider();
        VideoEngine engine = spec.engine();
        String effectiveModel = spec.effectiveModel();
        Integer duration = spec.duration();
        String ratio = (request.ratio() == null || request.ratio().isBlank()) ? "16:9" : request.ratio();

        // 任务类型 = (有无参考图) × (模型输出类型)
        List<String> imageUrls = request.imageUrls() == null ? Collections.emptyList() : request.imageUrls();
        List<String> videoUrls = request.videoUrls() == null ? Collections.emptyList() : request.videoUrls();
        List<String> audioUrls = request.audioUrls() == null ? Collections.emptyList() : request.audioUrls();
        boolean hasImages = !imageUrls.isEmpty();
        OutputType outputType = spec.outputType();
        GenerationMode mode = GenerationMode.of(hasImages, outputType);

        // 幂等键由调用方在重试时复用；未提供时生成一次性键（UI 单次点击仍安全）。
        String requestId = StringUtils.hasText(request.requestId())
                ? request.requestId().trim()
                : "req_" + UUID.randomUUID().toString().replace("-", "");
        if (requestId.length() > 128) {
            throw new IllegalArgumentException("生成请求幂等键过长");
        }
        if (request.userId() != null) {
            VideoTask existing = videoTaskService.getOne(
                    com.baomidou.mybatisplus.core.toolkit.Wrappers.<VideoTask>lambdaQuery()
                            .eq(VideoTask::getUserId, request.userId())
                            .eq(VideoTask::getRequestId, requestId)
                            .last("limit 1"), false);
            if (existing != null) {
                return existing;
            }
        }

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
        task.setRequestId(requestId);
        // 超时判定基准：本轮尝试起点（首次 = 创建时间）
        task.setLastAttemptAt(LocalDateTime.now());
        // 冻结金额先快照到任务再落库（结算/解冻用快照，防管理员改价后金额漂移）
        PricingService.Price freezePrice = pricingService.price(task);
        BigDecimal freezeAmount = freezePrice.amount();
        task.setFreezeAmount(freezeAmount);
        task.setFreezeUnitPrice(freezePrice.unitPrice());
        task.setFreezeCurrency(freezePrice.currency());
        videoTaskService.save(task);

        // 占并发槽位 + 预授权冻结（提交即占用额度）：任一失败 → 删除刚建的任务行并拒绝，不产生僵尸任务。
        // 冻结幂等（biz_key=task:{id}），超时重试不重复冻结；成功结算/失败解冻在终态入口统一处理。
        //
        // 槽位在钱之前：撞并发上限是常态（企业跑满是设计内的），先冻再退会在钱包流水里
        // 堆一大堆「冻结→退款」的成对记录，全是噪音，将来对账还得逐条解释它们；
        // 而且补偿成本一边是一次 ZREM，一边是一整个钱包事务。挑便宜的先做。
        try {
            admit(task);
            walletService.freeze(request.userId(), freezeAmount, task.getId());
        } catch (Exception e) {
            // ZREM 幂等，没占上也无害；顺序与占用相反，先放最外层的资源
            admissionControl.releaseQuietly(request.userId(), task.getId(), request.apiKeyId());
            videoTaskService.removeById(task.getId());
            throw e;
        }

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
                .nodeId(StringUtils.hasText(request.nodeId()) ? request.nodeId().trim() : null)
                .build();
        log.info("提交生成任务: provider={}, model={}, taskId={}, webhookUrl={}",
                provider, effectiveModel, task.businessTaskId(),
                maskToken(command.getWebhookUrl()));
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
        // 这里只完成冻结；用户消费记录和 SETTLE 必须等成功终态，不因提交成功而提前扣费。
        // 任务提交成功事件：异步提交
        applicationEventPublisher.publishEvent(new TaskSubmittedEvent(request.userId(), task.businessTaskId(), imageUrls));
        return task;
    }

    /**
     * 占一个在途并发槽位；超限则抛，由上面的 catch 统一补偿。
     * <p>
     * <b>个人用户走的是「一次 Redis 都不发」那条</b>：{@code resolve} 返回不限时
     * {@code acquire} 立刻返回 skipped，连 app_user 之外的任何额外开销都没有。
     * <p>
     * 这里刻意<b>不给 resolve 加缓存</b>：档位是管理员随时可改的，缓存会让「刚给客户开了 200 路」
     * 迟迟不生效，而这条查询是主键读。
     */
    private void admit(VideoTask task) {
        Long userId = task.getUserId();
        if (userId == null) {
            return; // 无属主的历史/内部任务不进这套
        }
        Long apiKeyId = task.getApiKeyId();
        // key 份额只有走 API 的请求才有（网页/画布没有 apiKeyId，只受账号总量管）
        ApiKey apiKey = apiKeyId == null ? null : apiKeyMapper.selectById(apiKeyId);
        ConcurrencyLimit limit = concurrencyPolicy.resolve(appUserMapper.selectById(userId), apiKey);
        if (limit.unlimited()) {
            return;
        }
        AdmissionResult result = admissionControl.acquire(userId, task.getId(), apiKeyId, limit);
        if (!result.admitted()) {
            throw new ConcurrencyLimitExceededException(result);
        }
    }

    /**
     * 超时自动重试：从已落库任务反推参数重新提交引擎。
     * 同一任务沿用原冻结金额，自动重跑不重复冻结；最终只成功结算一次。
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

    /**
     * submit 与 estimate 共用的口径解析：提供方默认值 → 生效模型 → 开放闸门 → 时长默认 → 输出类型。
     * 估价与冻结走同一个方法、同一个 PricingService 入口，「展示价 == 冻结价」在结构上不可漂移。
     */
    private ResolvedSpec resolveSpec(String requestProvider, String requestModel, Integer requestDuration) {
        String provider = resolveProvider(requestProvider);
        VideoEngine engine = videoEngineRegistry.get(provider);
        // 闸门基于「实际生效的模型」而非请求原始值（防不传/乱传 model 绕过）
        String effectiveModel = engine.effectiveModel(requestModel);
        assertModelOpen(effectiveModel);
        // 统一默认值（与 UI 控制器一致）：API 可能不传 duration
        Integer duration = requestDuration == null ? 8 : requestDuration;
        OutputType outputType = engine.outputType(effectiveModel);
        return new ResolvedSpec(provider, engine, effectiveModel, duration, outputType);
    }

    private record ResolvedSpec(String provider, VideoEngine engine, String effectiveModel,
                                Integer duration, OutputType outputType) {
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

    /**
     * 回调地址里带着 {@code ?token=<回调密钥>}，整条打进日志等于把密钥写进 docker logs ——
     * 而这个密钥线上与 {@code COMFYUI_ACCESS_TOKEN} 是同一个串，拿到它就能穿过 nginx 直接用 GPU。
     * 日志要能看出「回调配没配、发到哪台」，但不需要看到密钥本身。
     */
    private String maskToken(String url) {
        if (url == null || url.isBlank()) {
            return "无（轮询推进）";
        }
        return url.replaceAll("(?i)([?&]token=)[^&]*", "$1***");
    }

}
