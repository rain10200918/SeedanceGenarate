package org.example.seedancegenarate.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.example.seedancegenarate.dto.PromptTokenSummary;
import org.example.seedancegenarate.entity.PromptTokenUsage;

/**
 * 提示词优化 token 消耗：记录（切面写入）+ 管理端查询。
 */
public interface TokenUsageService {

    /** 记录一次 LLM 调用消耗（成功/失败都记）；调用方需自行兜底异常，不影响主流程 */
    void record(PromptTokenUsage usage);

    /** 分页明细（可按场景 / LLM 模型 / 状态过滤） */
    Page<PromptTokenUsage> page(long current, long size, String scene, String llmModel, String status);

    /** 汇总：今日与累计的调用次数 / token / 失败数 + 按场景分布 */
    PromptTokenSummary summary();
}
