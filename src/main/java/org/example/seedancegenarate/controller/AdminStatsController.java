package org.example.seedancegenarate.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.example.seedancegenarate.context.UserContext;
import org.example.seedancegenarate.dto.AdminDashboardResponse;
import org.example.seedancegenarate.dto.ApiCallLogView;
import org.example.seedancegenarate.dto.ApiCallSummary;
import org.example.seedancegenarate.entity.Result;
import org.example.seedancegenarate.service.AdminStatsService;
import org.springframework.web.bind.annotation.*;

/**
 * 管理端统计：全局看板 / API 调用明细与汇总（均 requireAdmin）。
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminStatsController {
    private final AdminStatsService adminStatsService;

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

    private void requireAdmin() {
        if (!UserContext.isAdmin()) {
            throw new RuntimeException("无权限访问");
        }
    }
}
