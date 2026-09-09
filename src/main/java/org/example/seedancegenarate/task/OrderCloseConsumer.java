package org.example.seedancegenarate.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.seedancegenarate.entity.AsyncJob;
import org.example.seedancegenarate.mapper.RechargeOrderMapper;
import org.example.seedancegenarate.service.AsyncJobService;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

/**
 * 充值订单超时关闭消费：领取 ORDER_CLOSE 作业 → CAS 关单（PENDING→CLOSED）→ 完成。
 * <p>
 * 与 TASK_FINALIZE 同构：行级租约保证多实例竞争安全；CAS 条件更新保证与回调入账
 * （PENDING→SUCCESS）互斥——先到先赢，另一侧影响 0 行自然跳过；biz_key=order:{orderNo}
 * 幂等防重复入队；Redis 通知丢失由 30s 兜底扫描接管（作业表在 MySQL，不依赖 Redis 持久）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderCloseConsumer implements AsyncJobHandler {
    public static final String JOB_TYPE_ORDER_CLOSE = "ORDER_CLOSE";

    /** 关单就是一条 UPDATE，租约短租足够。 */
    private static final long CLOSE_LEASE_SECONDS = 60;

    private final AsyncJobService asyncJobService;
    private final RechargeOrderMapper rechargeOrderMapper;
    private final ObjectMapper objectMapper;

    @Override
    public String jobType() {
        return JOB_TYPE_ORDER_CLOSE;
    }

    @Override
    public long leaseSeconds() {
        return CLOSE_LEASE_SECONDS;
    }

    @Override
    public void execute(AsyncJob job) {
        Payload payload = parse(job.getPayload());
        if (payload == null || !StringUtils.hasText(payload.orderNo())) {
            asyncJobService.complete(job);
            return;
        }
        try {
            int rows = rechargeOrderMapper.closePendingOrder(payload.orderNo(), LocalDateTime.now());
            if (rows > 0) {
                log.info("充值订单超时关闭: orderNo={}", payload.orderNo());
            } else {
                // 已被回调置 SUCCESS/CLOSED（或订单不存在）：关单使命完成，收掉作业
                log.info("充值订单无需关闭（已处理或不存在）: orderNo={}", payload.orderNo());
            }
            asyncJobService.complete(job);
        } catch (Exception e) {
            asyncJobService.failAndRetry(job, e.getMessage());
        }
    }

    private Payload parse(String payload) {
        if (!StringUtils.hasText(payload)) {
            return null;
        }
        try {
            JsonNode json = objectMapper.readTree(payload);
            JsonNode orderNo = json.get("orderNo");
            return new Payload(orderNo == null || orderNo.isNull() ? null : orderNo.asText());
        } catch (Exception e) {
            log.warn("解析关单作业参数失败: {}", payload);
            return null;
        }
    }

    private record Payload(String orderNo) {
    }
}
