package org.example.seedancegenarate.task;

import org.example.seedancegenarate.dto.ApiKeyBudgetAuthorization;
import org.example.seedancegenarate.entity.VideoTask;
import org.example.seedancegenarate.service.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import static org.mockito.Mockito.*;

class ApiKeyBudgetReconcileTaskTest {
    // 【测什么】扫描以预算授权为准，成功失败均补齐；未终态及UNKNOWN不能按年龄释放。
    // 【怎么算红】删除状态判断直接release会对第三条PROCESSING多调用一次。
    @Test void repairsTerminalReservationsWithoutWalletMissingFilter() {
        var budgets = mock(ApiKeyBudgetService.class); var billing = mock(BillingAuthorizationService.class);
        var tasks = mock(VideoTaskService.class);
        when(budgets.findReserved(0, 100)).thenReturn(List.of(hold(1), hold(2), hold(3)));
        var success = task(1, "SUCCESS"); var failed = task(2, "FAILED"); var unknown = task(3, "PROCESSING");
        unknown.setPhase("RECOVERY_REQUIRED");
        when(tasks.getById(1L)).thenReturn(success); when(tasks.getById(2L)).thenReturn(failed); when(tasks.getById(3L)).thenReturn(unknown);
        new ApiKeyBudgetReconcileTask(budgets, billing, tasks).reconcile();
        verify(billing).settle(success, BigDecimal.TEN); verify(billing).release(failed, BigDecimal.TEN);
        verifyNoMoreInteractions(billing);
    }

    // 【测什么】毒行失败后游标仍前进，后续任务可退款；扫描到底后回头重试。
    // 【怎么算红】异常中止循环或只成功才推进游标会遗漏第二条或无法从2继续。
    @Test void poisonRowDoesNotStarveLaterRefunds() {
        var budgets = mock(ApiKeyBudgetService.class); var billing = mock(BillingAuthorizationService.class);
        var tasks = mock(VideoTaskService.class); var runner = new ApiKeyBudgetReconcileTask(budgets, billing, tasks);
        var first = task(1, "FAILED"); var second = task(2, "FAILED");
        when(budgets.findReserved(0, 100)).thenReturn(List.of(hold(1), hold(2)));
        when(budgets.findReserved(2, 100)).thenReturn(List.of());
        when(tasks.getById(1L)).thenReturn(first); when(tasks.getById(2L)).thenReturn(second);
        doThrow(new IllegalStateException("bad ledger")).when(billing).release(first, BigDecimal.TEN);
        runner.reconcile(); runner.reconcile(); runner.reconcile();
        verify(billing, times(2)).release(second, BigDecimal.TEN);
        verify(budgets, times(2)).findReserved(0, 100); verify(budgets).findReserved(2, 100);
    }
    private ApiKeyBudgetAuthorization hold(long id) { return new ApiKeyBudgetAuthorization(1, 7, id, "2026-09", BigDecimal.TEN); }
    private VideoTask task(long id, String status) { var t = new VideoTask(); t.setId(id); t.setUserId(7L); t.setApiKeyId(1L); t.setStatus(status); return t; }
}
