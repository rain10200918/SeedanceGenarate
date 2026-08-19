package org.example.seedancegenarate.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.example.seedancegenarate.context.UserContext;
import org.example.seedancegenarate.dto.BalanceTransactionView;
import org.example.seedancegenarate.entity.Result;
import org.example.seedancegenarate.mapper.BalanceTransactionMapper;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端资金流水：全站流水分页（可按用户 / 类型筛选）。审计入口。
 */
@RestController
@RequestMapping("/api/admin/transactions")
@RequiredArgsConstructor
public class AdminTransactionController {

    private final BalanceTransactionMapper balanceTransactionMapper;

    @GetMapping
    public Result<Page<BalanceTransactionView>> page(
            @RequestParam(value = "userId", required = false) Long userId,
            @RequestParam(value = "type", required = false) String type,
            @RequestParam(value = "current", defaultValue = "1") Long current,
            @RequestParam(value = "size", defaultValue = "20") Long size
    ) {
        requireAdmin();
        long pageCurrent = Math.max(current, 1L);
        long pageSize = Math.min(Math.max(size, 1L), 100L);
        Page<BalanceTransactionView> page = balanceTransactionMapper.selectAdminPage(
                new Page<>(pageCurrent, pageSize),
                userId,
                StringUtils.hasText(type) ? type : null
        );
        return Result.success(page);
    }

    private void requireAdmin() {
        if (!UserContext.isAdmin()) {
            throw new RuntimeException("无权限访问");
        }
    }
}
