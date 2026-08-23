package org.example.seedancegenarate.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.seedancegenarate.config.AsyncJobProperties;
import org.example.seedancegenarate.entity.AsyncJob;
import org.example.seedancegenarate.mapper.RechargeOrderMapper;
import org.example.seedancegenarate.service.AsyncJobService;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderCloseConsumerTest {

    @Test
    void closesPendingOrderAndCompletesJob() {
        AsyncJobService jobs = mock(AsyncJobService.class);
        RechargeOrderMapper mapper = mock(RechargeOrderMapper.class);
        when(mapper.closePendingOrder(eq("ALP1"), any(LocalDateTime.class))).thenReturn(1);
        AsyncJob job = claimedJob(1L, "{\"orderNo\":\"ALP1\"}");
        when(jobs.claimBatch(eq("ORDER_CLOSE"), any(Integer.class), any(Long.class)))
                .thenReturn(List.of(job));
        OrderCloseConsumer consumer = new OrderCloseConsumer(jobs, mapper, properties(), new ObjectMapper());

        consumer.consumePendingCloses();

        verify(mapper).closePendingOrder(eq("ALP1"), any(LocalDateTime.class));
        verify(jobs).complete(1L, "token");
    }

    @Test
    void completesJobWithoutCloseWhenOrderAlreadyProcessedByNotify() {
        // 关单与回调入账竞态：回调先到（订单已 SUCCESS），关单 CAS 影响 0 行 → 无事可做，收掉作业
        AsyncJobService jobs = mock(AsyncJobService.class);
        RechargeOrderMapper mapper = mock(RechargeOrderMapper.class);
        when(mapper.closePendingOrder(eq("ALP1"), any(LocalDateTime.class))).thenReturn(0);
        AsyncJob job = claimedJob(1L, "{\"orderNo\":\"ALP1\"}");
        when(jobs.claimBatch(eq("ORDER_CLOSE"), any(Integer.class), any(Long.class)))
                .thenReturn(List.of(job));
        OrderCloseConsumer consumer = new OrderCloseConsumer(jobs, mapper, properties(), new ObjectMapper());

        consumer.consumePendingCloses();

        verify(jobs).complete(1L, "token");
        verify(jobs, never()).failAndRetry(eq(1L), eq("token"), any());
    }

    @Test
    void backsOffAndRetriesWhenCloseThrows() {
        AsyncJobService jobs = mock(AsyncJobService.class);
        RechargeOrderMapper mapper = mock(RechargeOrderMapper.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("db down"))
                .when(mapper).closePendingOrder(eq("ALP1"), any(LocalDateTime.class));
        AsyncJob job = claimedJob(1L, "{\"orderNo\":\"ALP1\"}");
        when(jobs.claimBatch(eq("ORDER_CLOSE"), any(Integer.class), any(Long.class)))
                .thenReturn(List.of(job));
        OrderCloseConsumer consumer = new OrderCloseConsumer(jobs, mapper, properties(), new ObjectMapper());

        consumer.consumePendingCloses();

        verify(jobs).failAndRetry(eq(1L), eq("token"), any());
    }

    @Test
    void completesJobWhenPayloadUnparsable() {
        AsyncJobService jobs = mock(AsyncJobService.class);
        RechargeOrderMapper mapper = mock(RechargeOrderMapper.class);
        AsyncJob job = claimedJob(1L, "not-json");
        when(jobs.claimBatch(eq("ORDER_CLOSE"), any(Integer.class), any(Long.class)))
                .thenReturn(List.of(job));
        OrderCloseConsumer consumer = new OrderCloseConsumer(jobs, mapper, properties(), new ObjectMapper());

        consumer.consumePendingCloses();

        verify(jobs).complete(1L, "token");
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
        return job;
    }

    private AsyncJobProperties properties() {
        AsyncJobProperties properties = new AsyncJobProperties();
        properties.setClaimBatchSize(20);
        properties.setLeaseSeconds(60);
        return properties;
    }
}
