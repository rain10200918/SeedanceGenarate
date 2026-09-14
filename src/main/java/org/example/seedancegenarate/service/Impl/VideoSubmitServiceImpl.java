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
import org.example.seedancegenarate.engine.ModelSpec;
import org.example.seedancegenarate.engine.VideoEngine;
import org.example.seedancegenarate.engine.VideoEngineRegistry;
import org.example.seedancegenarate.entity.ApiKey;
import org.example.seedancegenarate.entity.GenerationAttempt;
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
import org.example.seedancegenarate.service.AsyncJobService;
import org.example.seedancegenarate.service.GenerationAttemptService;
import org.example.seedancegenarate.service.GenerationParameters;
import org.example.seedancegenarate.service.PricingService;
import org.example.seedancegenarate.service.TaskStatusTransitioner;
import org.example.seedancegenarate.service.VideoSubmitService;
import org.example.seedancegenarate.service.VideoTaskService;
import org.example.seedancegenarate.service.WalletService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * 提交编排实现。从 {@code VideoController} 提取（UI/API 共用）：
 * 解析实际生效模型 → 开放闸门 → 落库/冻结/attempt/job；供应商 HTTP 只由 Worker 执行。
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
    private final GenerationAttemptService generationAttemptService;
    private final AsyncJobService asyncJobService;
    private final TransactionTemplate transactionTemplate;
    @org.springframework.beans.factory.annotation.Autowired
    private org.example.seedancegenarate.service.StoredImageReferences storedReferences;
    @org.springframework.beans.factory.annotation.Autowired
    private org.example.seedancegenarate.service.BillingAuthorizationService billingAuthorization;

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
    public VideoTask findAcceptedByRequestId(Long userId, String requestId) {
        VideoTask existing = findByRequestId(userId, requestId);
        return existing == null ? null : requireDurableRequestWinner(existing);
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
        return submitInternal(request,null);
    }

    @Override
    public VideoTask submitApproved(SubmitRequest request, PriceEstimate approved) throws Exception {
        if(approved==null || approved.amount()==null || approved.amount().signum()<0 || approved.currency()==null)
            throw BusinessException.badRequest("缺少有效的费用确认");
        return submitInternal(request,approved);
    }

    private VideoTask submitInternal(SubmitRequest request, PriceEstimate approved) throws Exception {
        String requestId = StringUtils.hasText(request.requestId())
                ? request.requestId().trim()
                : "req_" + UUID.randomUUID().toString().replace("-", "");
        if (requestId.length() > 128) {
            throw BusinessException.badRequest("生成请求幂等键过长");
        }
        // 已受理身份优先：模型下架、能力变化或旧引用失效都不能阻断原请求重放。
        VideoTask existing = findAcceptedByRequestId(request.userId(), requestId);
        if (existing != null) return existing;

        validatePinnedNode(request.provider(), request.nodeId());
        ResolvedSpec spec = resolveSpec(request.provider(), request.model(), request.duration());
        String provider = spec.provider();
        String effectiveModel = spec.effectiveModel();
        Integer duration = spec.duration();

        // 任务类型 = (有无参考图) × (模型输出类型)
        List<String> imageUrls = request.imageUrls() == null ? Collections.emptyList() : request.imageUrls();
        List<String> videoUrls = request.videoUrls() == null ? Collections.emptyList() : request.videoUrls();
        List<String> audioUrls = request.audioUrls() == null ? Collections.emptyList() : request.audioUrls();
        var stored=request.storedImageReferences()==null?List.<org.example.seedancegenarate.service.StoredImageReferences.Reference>of():request.storedImageReferences();
        String requestedRatio = request.ratio();
        // 旧UI/Agent为音频补16:9；仅无API key的音频空能力模型兼容这一占位。
        if (request.apiKeyId() == null && spec.outputType() == OutputType.AUDIO
                && spec.modelSpec().ratios() != null && spec.modelSpec().ratios().isEmpty()
                && "16:9".equals(requestedRatio)) {
            requestedRatio = null;
        }
        GenerationParameters parameters = GenerationParameters.validate(spec.modelSpec(), request.duration(),
                requestedRatio, request.megapixels(), imageUrls.size() + stored.size(),
                videoUrls.size(), audioUrls.size());
        String ratio = parameters.ratio();
        if(!stored.isEmpty()) {
            if(storedReferences==null || stored.size()!=1 || !imageUrls.isEmpty()) throw new IllegalArgumentException("内部参考图片无效");
            for(var reference:stored) storedReferences.validate(request.userId(),reference);
        }
        OutputType outputType = spec.outputType();

        // 业务 ID 在调用外部提供方之前生成：后续异步 Worker 即使尚未拿到 providerTaskId，
        // 也能立即对外返回稳定的任务标识。taskId 暂作为兼容别名，保持现有 UI/API 契约。
        String bizTaskId = "tsk_" + UUID.randomUUID().toString().replace("-", "");
        VideoTask task = new VideoTask();
        task.setUserId(request.userId());
        task.setBizTaskId(bizTaskId);
        task.setTaskId(bizTaskId);
        task.setPrompt(request.prompt());
        task.setImages(imageUrls.isEmpty()?null:objectMapper.writeValueAsString(imageUrls));
        task.setStoredImageReferences(stored.isEmpty()?null:objectMapper.writeValueAsString(stored));
        task.setReferenceVideos(videoUrls.isEmpty() ? null : objectMapper.writeValueAsString(videoUrls));
        task.setReferenceAudios(audioUrls.isEmpty() ? null : objectMapper.writeValueAsString(audioUrls));
        task.setDuration(duration);
        task.setRatio(ratio);
        task.setStatus("PROCESSING");
        task.setProvider(provider);
        task.setModel(effectiveModel);
        task.setOutputType(outputType.name());
        task.setMegapixels(parameters.megapixels());
        task.setApiKeyId(request.apiKeyId());
        task.setRequestId(requestId);
        // 超时判定基准：本轮尝试起点（首次 = 创建时间）
        task.setLastAttemptAt(LocalDateTime.now());
        // 冻结金额先快照到任务再落库（结算/解冻用快照，防管理员改价后金额漂移）
        PricingService.Price freezePrice = pricingService.price(task);
        BigDecimal freezeAmount = freezePrice.amount();
        if(approved!=null && (!java.util.Objects.equals(approved.provider(),provider)
                || !java.util.Objects.equals(approved.model(),effectiveModel)
                || !java.util.Objects.equals(approved.duration(),duration)
                || !java.util.Objects.equals(approved.outputType(),outputType.name())
                || !java.util.Objects.equals(approved.currency(),freezePrice.currency())
                || freezeAmount==null || freezeAmount.compareTo(approved.amount())!=0))
            throw BusinessException.conflict("生成报价发生变化，请重新确认费用");
        task.setFreezeAmount(freezeAmount);
        task.setFreezeUnitPrice(freezePrice.unitPrice());
        task.setFreezeCurrency(freezePrice.currency());
        try {
            if (!videoTaskService.save(task) || task.getId() == null) {
                throw new IllegalStateException("创建生成任务失败");
            }
        } catch (DuplicateKeyException duplicate) {
            // 两个实例可能同时通过上面的快查；唯一键决定赢家，输家直接返回赢家且不产生副作用。
            VideoTask winner = findByRequestId(request.userId(), requestId);
            if (winner != null) {
                return requireDurableRequestWinner(winner);
            }
            throw duplicate;
        }

        // 占并发槽位 + 预授权冻结（提交即占用额度）：任一失败 → 删除刚建的任务行并拒绝，不产生僵尸任务。
        // 冻结幂等（biz_key=task:{id}），超时重试不重复冻结；成功结算/失败解冻在终态入口统一处理。
        //
        // 槽位在钱之前：撞并发上限是常态（企业跑满是设计内的），先冻再退会在钱包流水里
        // 堆一大堆「冻结→退款」的成对记录，全是噪音，将来对账还得逐条解释它们；
        // 而且补偿成本一边是一次 ZREM，一边是一整个钱包事务。挑便宜的先做。
        try {
            admit(task);
            GenerationAttempt attempt = transactionTemplate.execute(status -> {
                if (task.getApiKeyId() == null) {
                    walletService.freeze(request.userId(), freezeAmount, task.getId());
                } else {
                    billingAuthorization.freeze(task, freezeAmount);
                }
                GenerationAttempt staged = generationAttemptService.stageCurrentAttempt(
                        task, 1, request.nodeId());
                asyncJobService.enqueue(GenerationAttemptService.JOB_TYPE,
                        GenerationAttemptService.jobKey(staged.getId()), jobPayload(staged.getId()));
                return staged;
            });
            if (attempt == null) {
                throw new IllegalStateException("创建生成提交作业失败");
            }
        } catch (Exception e) {
            // commit outcome unknown 时不能直接做破坏性补偿：事务可能已经提交，只是客户端没收到确认。
            // 必须从数据库重新确认；一旦 attempt/job 的受理事实可见，就按成功受理返回。
            VideoTask persisted;
            try {
                persisted = findByRequestId(request.userId(), requestId);
            } catch (Exception recheckFailure) {
                // DB 自身也无法证明事务未提交时，宁可交给对账回收，也不能删除可能已受理的任务。
                e.addSuppressed(recheckFailure);
                log.error("生成提交事务结果未知且持久化复查失败，跳过破坏性补偿: taskId={}, requestId={}",
                        task.businessTaskId(), requestId, recheckFailure);
                throw e;
            }
            if (isDurableRequestWinner(persisted)) {
                log.warn("生成提交事务返回异常但 durable attempt 已可见，按已受理返回: taskId={}, requestId={}",
                        persisted.businessTaskId(), requestId);
                publishAfterCommit(new TaskSubmittedEvent(
                        request.userId(), persisted.businessTaskId(), imageUrls));
                return persisted;
            }
            // ZREM 幂等，没占上也无害；顺序与占用相反，先放最外层的资源
            admissionControl.releaseQuietly(request.userId(), task.getId(), request.apiKeyId());
            videoTaskService.removeById(task.getId());
            throw e;
        }
        log.info("生成任务已持久化排队: provider={}, model={}, taskId={}, attemptId={}",
                provider, effectiveModel, task.businessTaskId(), task.getCurrentAttemptId());
        // 事务已经提交后再发事件；订阅者不会读到尚未可见的 task/attempt/job。
        publishAfterCommit(new TaskSubmittedEvent(request.userId(), task.businessTaskId(), imageUrls));
        return task;
    }

    /**
     * task 首行是独立 autocommit，短事务完成前并不代表请求已经可靠受理。
     * 此时赢家仍可能因 admission/freeze/attempt/job 失败而补偿删除，不能把这个 ghost taskId 暴露出去。
     */
    private VideoTask requireDurableRequestWinner(VideoTask winner) {
        if (isDurableRequestWinner(winner)) {
            return winner;
        }
        throw BusinessException.conflict("同一请求正在受理，请稍后使用相同 requestId 重试");
    }

    private boolean isDurableRequestWinner(VideoTask winner) {
        if (winner == null) {
            return false;
        }
        if ("SUCCESS".equals(winner.getStatus()) || "FAILED".equals(winner.getStatus())) {
            return true;
        }
        return "PROCESSING".equals(winner.getStatus())
                && (winner.getCurrentAttemptId() != null
                || StringUtils.hasText(winner.getPhase())
                || StringUtils.hasText(winner.getProviderTaskId()));
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

    private String jobPayload(Long attemptId) {
        try {
            return objectMapper.writeValueAsString(new GenerationAttemptService.JobPayload(attemptId));
        } catch (Exception e) {
            throw new IllegalStateException("序列化生成提交作业失败", e);
        }
    }

    private void publishAfterCommit(TaskSubmittedEvent event) {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    applicationEventPublisher.publishEvent(event);
                }
            });
            return;
        }
        applicationEventPublisher.publishEvent(event);
    }

    /**
     * 超时自动重试：原子切换到新 attempt 并持久化 GENERATION_SUBMIT job。
     * 同一任务沿用原冻结金额，自动重跑不重复冻结；最终只成功结算一次。
     * <p>
     * 并发安全：事务内先 CAS retry_count/current_attempt/phase，同时清掉旧远端标识，再 stage + enqueue；
     * 多实例 Worker 竞争时只有一方能创建下一轮，另一方返回 false 收工。
     *
     * @return true=本次已把新轮次排队；false=被其他实例抢先或任务已终态
     */
    public boolean resubmit(VideoTask task) throws Exception {
        if (task == null || task.getId() == null || !"PROCESSING".equals(task.getStatus())) {
            return false;
        }
        // 已经排队/提交中/待人工恢复，说明这个 TASK_RETRY 已完成或已经失去时机。
        if (StringUtils.hasText(task.getPhase()) && !"RUNNING".equals(task.getPhase())) {
            return false;
        }
        String effectiveModel = task.getModel();
        // 重试时模型可能已被管理员关闭 → 不再重试，走失败
        assertModelOpen(effectiveModel);
        int currentRetry = task.getRetryCount() == null ? 0 : task.getRetryCount();
        Boolean staged = transactionTemplate.execute(status -> {
            LambdaUpdateWrapper<VideoTask> claim = new LambdaUpdateWrapper<VideoTask>()
                    .eq(VideoTask::getId, task.getId())
                    .eq(VideoTask::getStatus, "PROCESSING")
                    .eq(VideoTask::getRetryCount, currentRetry);
            if (task.getCurrentAttemptId() == null) {
                claim.isNull(VideoTask::getCurrentAttemptId);
            } else {
                claim.eq(VideoTask::getCurrentAttemptId, task.getCurrentAttemptId());
            }
            if (task.getPhase() == null) {
                claim.isNull(VideoTask::getPhase);
            } else {
                claim.eq(VideoTask::getPhase, task.getPhase());
            }
            claim.set(VideoTask::getProviderTaskId, null)
                    .set(VideoTask::getNodeId, null)
                    .set(VideoTask::getRetryCount, currentRetry + 1)
                    .set(VideoTask::getLastAttemptAt, LocalDateTime.now())
                    .set(VideoTask::getNextPollAt, null);
            if (!videoTaskService.update(claim)) {
                return false;
            }
            task.setRetryCount(currentRetry + 1);
            task.setProviderTaskId(null);
            task.setNodeId(null);
            GenerationAttempt attempt = generationAttemptService.stageCurrentAttempt(
                    task, currentRetry + 2, null);
            asyncJobService.enqueue(GenerationAttemptService.JOB_TYPE,
                    GenerationAttemptService.jobKey(attempt.getId()), jobPayload(attempt.getId()));
            return true;
        });
        if (Boolean.TRUE.equals(staged)) {
            log.info("超时自动重试已排队: taskId={}, model={}, 第 {} 次重试, attemptId={}",
                    task.businessTaskId(), effectiveModel, currentRetry + 1, task.getCurrentAttemptId());
            return true;
        }
        log.warn("重试排队被抢先（任务已终态或已被重试）: taskId={}", task.businessTaskId());
        return false;
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
        ModelSpec modelSpec = engine.models().stream()
                .filter(candidate -> effectiveModel.equals(candidate.model()))
                .findFirst().orElseThrow(() -> BusinessException.badRequest("模型能力不可用"));
        Integer duration = GenerationParameters.resolveDuration(modelSpec, requestDuration);
        return new ResolvedSpec(provider, engine, effectiveModel, duration, modelSpec.outputType(), modelSpec);
    }

    private record ResolvedSpec(String provider, VideoEngine engine, String effectiveModel,
                                Integer duration, OutputType outputType, ModelSpec modelSpec) {
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
