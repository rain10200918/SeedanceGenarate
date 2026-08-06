package org.example.seedancegenarate.event;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.seedancegenarate.entity.ApiCallLog;
import org.example.seedancegenarate.mapper.ApiCallLogMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 调用日志终态收尾：任务落库为 SUCCESS/FAILED 后（AFTER_COMMIT），把对应 api_call_log
 * 从 RECEIVED 更新为终态，补齐金额与端到端耗时（total_ms）。与 SSE/webhook 同源，
 * 只监听一次事件，无重复写。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApiCallLogUpdater {

    private final ApiCallLogMapper apiCallLogMapper;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onStatusChanged(TaskStatusChangedEvent event) {
        if (event.message() == null || event.message().taskId() == null) {
            return;
        }
        try {
            ApiCallLog callLog = apiCallLogMapper.selectOne(Wrappers.<ApiCallLog>lambdaQuery()
                    .eq(ApiCallLog::getTaskId, event.message().taskId()));
            if (callLog == null || !"RECEIVED".equals(callLog.getStatus())) {
                return; // 非 API 来源（UI 任务）或已收尾
            }
            ApiCallLog update = new ApiCallLog();
            update.setId(callLog.getId());
            update.setStatus(event.message().status());
            update.setErrorMsg(event.message().errorMsg() == null ? null
                    : org.springframework.util.StringUtils.hasText(event.message().errorMsg())
                            ? event.message().errorMsg() : null);
            if (event.message().costAmount() != null) {
                update.setCostAmount(event.message().costAmount());
            }
            LocalDateTime start = callLog.getCreateTime();
            if (start != null) {
                update.setTotalMs(Duration.between(start, LocalDateTime.now()).toMillis());
            }
            apiCallLogMapper.updateById(update);
        } catch (Exception e) {
            log.warn("更新 API 调用日志终态失败: {}", e.getMessage());
        }
    }
}
