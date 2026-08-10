package org.example.seedancegenarate.service;

import org.example.seedancegenarate.entity.VideoTask;

import java.util.List;

/**
 * 生成任务提交编排的共享服务——UI（VideoController）与对外 API（/api/v1）共用同一份逻辑，
 * 保证两条入口的模型解析、开放闸门、落库、引擎提交、计费完全一致（单一事实源）。
 */
public interface VideoSubmitService {

    /**
     * 提交前校验（引擎存在 / 模型开放），无副作用。各入口在产生副作用（传参考图）之前调用。
     */
    void validate(String provider, String model);

    /**
     * 完整提交编排：解析实际生效模型 → 开放闸门 → 生成业务 ID 并落库 PROCESSING
     * → 引擎提交 → 回写 providerTaskId/nodeId。
     * 提交成功后立即计费（ON_SUBMIT 提供方，如 Seedance；幂等）。
     */
    VideoTask submit(SubmitRequest request) throws Exception;

    /** 统一提交入参；{@code imageUrls}/{@code videoUrls}/{@code audioUrls} 为已上传到 OSS 的参考素材 URL（两条入口各自负责素材获取）。 */
    record SubmitRequest(
            Long userId,
            String provider,
            String model,
            String prompt,
            List<String> imageUrls,
            List<String> videoUrls,
            List<String> audioUrls,
            Integer duration,
            String ratio,
            Double megapixels,
            Long apiKeyId
    ) {
    }
}
