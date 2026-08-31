package org.example.seedancegenarate.service;

import org.example.seedancegenarate.dto.ModelPricingView;

import java.util.List;

/**
 * 用户端模型与算力定价服务（带 Redis 缓存加速与管理员改价失效机制）
 */
public interface PublicModelPricingService {

    /**
     * 获取对外模型定价列表
     *
     * @param includeClosed 是否包含未开放模型（管理员查看时为 true）
     * @return 模型定价列表
     */
    List<ModelPricingView> getPublicModels(boolean includeClosed);

    /**
     * 清理 Redis 模型定价缓存（管理员改价或调整开关后调用）
     */
    void clearCache();
}
