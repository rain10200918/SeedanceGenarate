package org.example.seedancegenarate.engine;

import org.example.seedancegenarate.entity.VideoTask;

import java.util.List;

/**
 * 视频生成引擎（策略）。每个提供方（Seedance / ComfyUI / ...）实现一份，
 * 由 {@link VideoEngineRegistry} 按 {@link #provider()} 选择。
 * <p>
 * 提供方 = 新增一个实现类
 */
public interface VideoEngine {

    /** 提供方标识，作为注册表的 key */
    String provider();

    /** 提交生成任务，返回提供方任务 ID（及节点信息） */
    SubmitResult submit(GenerateCommand command) throws Exception;

    /**
     * 查询任务状态，返回归一化结果。
     */
    RemoteStatus poll(VideoTask task) throws Exception;

    /**
     * 用户计费时机。统一采用成功结算：提交只冻结用户额度，成功后才形成消费流水，失败释放冻结。
     * 外部提供方自身的成本结算不属于本接口的用户账务；不要用它决定超时重试策略。
     */
    default BillingTiming billingTiming() {
        return BillingTiming.ON_SUCCESS;
    }

    /**
     * 超时后是否允许框架免费重提交。与用户计费时机故意分离：成功才扣费不代表提供方一定支持自动重试。
     */
    default boolean timeoutRetrySupported() {
        return false;
    }

    /** 该提供方对外可选的模型 / 能力清单，供 /options 接口下发前端。 */
    List<ModelSpec> models();

    /** 提供方展示名，供前端下拉展示；默认用 provider 标识。 */
    default String displayName() {
        return provider();
    }

    /**
     * 指定模型的产物类型（视频 / 图片）。从 {@link #models()} 里按 model 查 {@link ModelSpec#outputType()}，
     * 查不到默认 {@link OutputType#VIDEO}。供提交时确定任务的输出维度并落库。
     */
    default OutputType outputType(String model) {
        return models().stream()
                .filter(spec -> spec.model().equals(model))
                .map(ModelSpec::outputType)
                .findFirst()
                .orElse(OutputType.VIDEO);
    }

    /**
     * 解析本次提交<b>实际生效</b>的模型标识——闸门（模型开放控制）、落库、提交命令都以它为准。
     * 请求显式指定则用之（trim 后）；为空则取该引擎的默认（第一个）模型。
     * <p>
     * 引擎若根本不认请求里的 model（如 Seedance 始终用 yaml 配置的默认模型，请求参数无效），
     * 必须覆写此方法，否则用户不传 model / 传任意 model 都能绕过模型开放闸门。
     */
    default String effectiveModel(String requestedModel) {
        if (requestedModel != null && !requestedModel.isBlank()) {
            return requestedModel.trim();
        }
        return models().stream().map(ModelSpec::model).findFirst().orElse(null);
    }

    /**
     * 任务完成通知机制。默认 {@link CompletionMechanism#POLL}（轮询）——
     * 支持回调 / 推送的提供方覆写为 {@link CompletionMechanism#CALLBACK} 并实现
     * {@link #parseCallbackTaskId(String)} / {@link #handleCallback(VideoTask, String)}。
     */
    default CompletionMechanism completionMechanism() {
        return CompletionMechanism.POLL;
    }

    /**
     * 该引擎的任务是否需要框架轮询推进。默认按机制：POLL=需要，CALLBACK=不需要。
     * 声明 CALLBACK 但实际未配置回调的引擎（如开发环境）应覆写返回 true，
     * 保证没有回调时也能由轮询/对账推进。
     */
    default boolean needsPolling() {
        return completionMechanism() == CompletionMechanism.POLL;
    }

    /** CALLBACK 引擎：从回调 body 提取提供方任务 ID（用于反查任务）。 */
    default String parseCallbackTaskId(String payload) {
        throw new UnsupportedOperationException("该提供方不支持回调");
    }

    /**
     * ETA（预计完成时间）能力声明。默认 {@link EtaCapability#BASIC}：
     * 基于历史平均耗时做时间估算；能查真实队列的引擎（如自建 ComfyUI）覆写为
     * {@link EtaCapability#FULL} 并实现 {@link #queuePosition(VideoTask)}。
     */
    default EtaCapability etaCapability() {
        return EtaCapability.BASIC;
    }

    /** FULL 引擎：查询任务在提供方队列中的位置（0 = 下一个执行）；不在队列返回 null。 */
    default Integer queuePosition(VideoTask task) throws Exception {
        throw new UnsupportedOperationException("该提供方不支持队列查询");
    }

    /** CALLBACK 引擎：处理回调，返回归一化状态（通常复用 poll 查询提供方最新状态）。 */
    default RemoteStatus handleCallback(VideoTask task, String payload) throws Exception {
        throw new UnsupportedOperationException("该提供方不支持回调");
    }
}
