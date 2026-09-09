package org.example.seedancegenarate.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.seedancegenarate.entity.AsyncJob;
import org.example.seedancegenarate.mapper.RechargeOrderMapper;
import org.example.seedancegenarate.service.AsyncJobService;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderCloseConsumerTest {

    @Test
    // 【测什么】待支付订单 CAS 关闭后收掉当前 fenced 作业。
    // 【怎么算红】删掉关单或 complete(job) 任一调用，这条必须变红。
    void closesPendingOrderAndCompletesJob() {
        AsyncJobService jobs = mock(AsyncJobService.class);
        RechargeOrderMapper mapper = mock(RechargeOrderMapper.class);
        when(mapper.closePendingOrder(eq("ALP1"), any(LocalDateTime.class))).thenReturn(1);
        AsyncJob job = claimedJob(1L, "{\"orderNo\":\"ALP1\"}");
        OrderCloseConsumer consumer = new OrderCloseConsumer(jobs, mapper, new ObjectMapper());

        consumer.execute(job);

        verify(mapper).closePendingOrder(eq("ALP1"), any(LocalDateTime.class));
        verify(jobs).complete(job);
    }

    @Test
    // 【测什么】支付回调已先处理订单时，关单作业幂等完成且不重试。
    // 【怎么算红】将关单 0 行视为失败调 failAndRetry，这条必须变红。
    void completesJobWithoutCloseWhenOrderAlreadyProcessedByNotify() {
        // 关单与回调入账竞态：回调先到（订单已 SUCCESS），关单 CAS 影响 0 行 → 无事可做，收掉作业
        AsyncJobService jobs = mock(AsyncJobService.class);
        RechargeOrderMapper mapper = mock(RechargeOrderMapper.class);
        when(mapper.closePendingOrder(eq("ALP1"), any(LocalDateTime.class))).thenReturn(0);
        AsyncJob job = claimedJob(1L, "{\"orderNo\":\"ALP1\"}");
        OrderCloseConsumer consumer = new OrderCloseConsumer(jobs, mapper, new ObjectMapper());

        consumer.execute(job);

        verify(jobs).complete(job);
        verify(jobs, never()).failAndRetry(eq(job), any());
    }

    @Test
    // 【测什么】关单 SQL 异常时由当前 lease 记录失败并退避。
    // 【怎么算红】catch 后吞异常或不调 failAndRetry(job,...)，这条必须变红。
    void backsOffAndRetriesWhenCloseThrows() {
        AsyncJobService jobs = mock(AsyncJobService.class);
        RechargeOrderMapper mapper = mock(RechargeOrderMapper.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("db down"))
                .when(mapper).closePendingOrder(eq("ALP1"), any(LocalDateTime.class));
        AsyncJob job = claimedJob(1L, "{\"orderNo\":\"ALP1\"}");
        OrderCloseConsumer consumer = new OrderCloseConsumer(jobs, mapper, new ObjectMapper());

        consumer.execute(job);

        verify(jobs).failAndRetry(eq(job), any());
    }

    @Test
    // 【测什么】毒 payload 不进入订单 SQL，直接收掉作业。
    // 【怎么算红】解析失败仍调关单 SQL 或不 complete，这条必须变红。
    void completesJobWhenPayloadUnparsable() {
        AsyncJobService jobs = mock(AsyncJobService.class);
        RechargeOrderMapper mapper = mock(RechargeOrderMapper.class);
        AsyncJob job = claimedJob(1L, "not-json");
        OrderCloseConsumer consumer = new OrderCloseConsumer(jobs, mapper, new ObjectMapper());

        consumer.execute(job);

        verify(jobs).complete(job);
        verify(mapper, never()).closePendingOrder(any(), any());
    }

    private AsyncJob claimedJob(Long id, String payload) {
        AsyncJob job = new AsyncJob();
        job.setId(id);
        job.setJobType("ORDER_CLOSE");
        job.setPayload(payload);
        job.setStatus(AsyncJob.STATUS_RUNNING);
        job.setAttempts(0);
        job.setMaxAttempts(5);
        job.setLeaseToken("token");
        job.setLeaseGeneration(1L);
        return job;
    }

}
