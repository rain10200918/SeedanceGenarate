package org.example.seedancegenarate.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.example.seedancegenarate.context.UserContext;
import org.example.seedancegenarate.dto.AdminDashboardResponse;
import org.example.seedancegenarate.dto.ApiCallLogView;
import org.example.seedancegenarate.dto.ApiCallSummary;
import org.example.seedancegenarate.dto.PromptTokenSummary;
import org.example.seedancegenarate.dto.SystemStatus;
import org.example.seedancegenarate.entity.PromptTokenUsage;
import org.example.seedancegenarate.entity.Result;
import org.example.seedancegenarate.service.AdminStatsService;
import org.example.seedancegenarate.service.SystemStatusService;
import org.example.seedancegenarate.service.TokenUsageService;
import org.springframework.web.bind.annotation.*;

/**
 * 管理端统计：全局看板 / API 调用明细与汇总 / 提示词优化 token 消耗（均 requireAdmin）。
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminStatsController {
    private final AdminStatsService adminStatsService;
    private final TokenUsageService tokenUsageService;
    private final SystemStatusService systemStatusService;

    /** 全局看板：今日/累计任务与消费、成功率、7 天趋势、模型分布、消费 TOP 用户 */
    @GetMapping("/dashboard")
    public Result<AdminDashboardResponse> dashboard() {
        requireAdmin();
        return Result.success(adminStatsService.dashboard());
    }

    /** API 调用明细（分页，可过滤钥匙/模型/提供方/状态/错误码） */
    @GetMapping("/api-calls")
    public Result<Page<ApiCallLogView>> apiCalls(
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) Long apiKeyId,
            @RequestParam(required = false) String model,
            @RequestParam(required = false) String provider,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String errorCode
    ) {
        requireAdmin();
        return Result.success(adminStatsService.apiCalls(current, size, apiKeyId, model, provider, status, errorCode));
    }

    /** API 调用汇总：状态分布 + 拒绝原因分布 */
    @GetMapping("/api-calls/summary")
    public Result<ApiCallSummary> apiCallSummary(
            @RequestParam(required = false) Long apiKeyId
    ) {
        requireAdmin();
        return Result.success(adminStatsService.apiCallSummary(apiKeyId));
    }

    /** 提示词优化 token 消耗明细（分页，可按场景 / LLM 模型 / 状态过滤） */
    @GetMapping("/token-usage")
    public Result<Page<PromptTokenUsage>> tokenUsage(
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String scene,
            @RequestParam(required = false) String llmModel,
            @RequestParam(required = false) String status
    ) {
        requireAdmin();
        return Result.success(tokenUsageService.page(current, size, scene, llmModel, status));
    }

    /** 提示词优化 token 消耗汇总：今日/累计调用与 token、失败数、按场景分布 */
    @GetMapping("/token-usage/summary")
    public Result<PromptTokenSummary> tokenUsageSummary() {
        requireAdmin();
        return Result.success(tokenUsageService.summary());
    }

    /** 系统状态：卡死任务 / 生成中 / 成功率 / 死信作业 / 节点（管理端监控页） */
    @GetMapping("/system-status")
    public Result<SystemStatus> systemStatus() {
        requireAdmin();
        return Result.success(systemStatusService.current());
    }

    private void requireAdmin() {
        if (!UserContext.isAdmin()) {
            throw new RuntimeException("无权限访问");
        }
    }
}
