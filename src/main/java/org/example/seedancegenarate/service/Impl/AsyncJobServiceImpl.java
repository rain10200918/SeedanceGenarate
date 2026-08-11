package org.example.seedancegenarate.service.Impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.seedancegenarate.config.AsyncJobProperties;
import org.example.seedancegenarate.entity.AsyncJob;
import org.example.seedancegenarate.mapper.AsyncJobMapper;
import org.example.seedancegenarate.service.AsyncJobService;
import org.example.seedancegenarate.service.JobAvailableNotifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** MySQL 作业表实现：biz_key 唯一幂等入队，行级租约领取。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AsyncJobServiceImpl implements AsyncJobService {
    private final AsyncJobMapper asyncJobMapper;
    private final AsyncJobProperties properties;
    private final JobAvailableNotifier jobAvailableNotifier;

    @Override
    public void enqueue(String jobType, String bizKey, String payload) {
        if (!StringUtils.hasText(jobType) || !StringUtils.hasText(bizKey)) {
            return;
        }
        // 只有「实际入队或重置」才通知（active 作业重复 enqueue 影响 0 行），
        // 避免对账/轮询重复 poll 已完成任务时反复发通知刷频道。
        int rows = asyncJobMapper.enqueueUpsert(jobType.trim(), bizKey.trim(), payload,
                Math.max(properties.getMaxAttempts(), 1));
        if (rows > 0) {
            // 事件驱动：唤醒消费 Worker 立即处理，避免轮询空转
            jobAvailableNotifier.notify(jobType.trim());
        }
    }

    @Override
    public List<AsyncJob> claimBatch(String jobType, int batchSize, long leaseSeconds) {
        List<AsyncJob> candidates = asyncJobMapper.selectList(Wrappers.<AsyncJob>lambdaQuery()
                .eq(AsyncJob::getJobType, jobType)
                .eq(AsyncJob::getStatus, AsyncJob.STATUS_READY)
                .le(AsyncJob::getAvailableAt, LocalDateTime.now())
                .orderByAsc(AsyncJob::getId)
                .last("limit " + Math.max(batchSize, 1)));
        List<AsyncJob> claimed = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        for (AsyncJob candidate : candidates) {
            String token = UUID.randomUUID().toString();
            int rows = asyncJobMapper.claim(candidate.getId(), instanceId(), token, now,
                    now.plusSeconds(leaseSeconds));
            if (rows == 1) {
                AsyncJob claimedJob = new AsyncJob();
                claimedJob.setId(candidate.getId());
                claimedJob.setJobType(candidate.getJobType());
                claimedJob.setBizKey(candidate.getBizKey());
                claimedJob.setPayload(candidate.getPayload());
                claimedJob.setLeaseToken(token);
                claimedJob.setAttempts(candidate.getAttempts());
                claimedJob.setMaxAttempts(candidate.getMaxAttempts());
                claimed.add(claimedJob);
            }
            // 未抢到的行（另一 Worker 刚领取）直接跳过，下一轮再试
        }
        return claimed;
    }

    @Override
    @Transactional
    public AsyncJob claim(String jobType, String bizKey, long leaseSeconds) {
        AsyncJob job = asyncJobMapper.selectOne(Wrappers.<AsyncJob>lambdaQuery()
                .eq(AsyncJob::getJobType, jobType)
                .eq(AsyncJob::getBizKey, bizKey)
                .eq(AsyncJob::getStatus, AsyncJob.STATUS_READY)
                .le(AsyncJob::getAvailableAt, LocalDateTime.now())
                .last("limit 1"));
        if (job == null) {
            return null;
        }
        String token = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();
        int rows = asyncJobMapper.claim(job.getId(), instanceId(), token, now,
                now.plusSeconds(leaseSeconds));
        if (rows != 1) {
            return null;
        }
        job.setLeaseToken(token);
        return job;
    }

    @Override
    public AsyncJob find(String jobType, String bizKey) {
        return asyncJobMapper.selectOne(Wrappers.<AsyncJob>lambdaQuery()
                .eq(AsyncJob::getJobType, jobType)
                .eq(AsyncJob::getBizKey, bizKey)
                .last("limit 1"));
    }

    @Override
    public void complete(Long jobId, String leaseToken) {
        asyncJobMapper.complete(jobId, leaseToken);
    }

    @Override
    public void failAndRetry(Long jobId, String leaseToken, String error) {
        AsyncJob job = asyncJobMapper.selectById(jobId);
        int attempts = job == null || job.getAttempts() == null ? 0 : job.getAttempts();
        int maxAttempts = job == null || job.getMaxAttempts() == null
                ? properties.getMaxAttempts() : job.getMaxAttempts();
        boolean dead = attempts + 1 >= maxAttempts;
        LocalDateTime backoff = LocalDateTime.now().plusSeconds(backoffSeconds(attempts));
        asyncJobMapper.failAndRetry(jobId, leaseToken,
                dead ? AsyncJob.STATUS_DEAD : AsyncJob.STATUS_READY,
                backoff, truncate(error));
        if (dead) {
            log.warn("作业超过重试上限进入 DEAD: jobId={}", jobId);
        }
    }

    private long backoffSeconds(int attempts) {
        long base = Math.max(properties.getBackoffBaseSeconds(), 1);
        return Math.min(base * (1L << Math.min(attempts, 10)), 3600);
    }

    private String instanceId() {
        return System.getProperty("user.name", "app") + "-"
                + java.net.InetAddress.getLoopbackAddress().getHostName() + "-"
                + UUID.randomUUID().toString().substring(0, 8);
    }

    private String truncate(String message) {
        if (message == null) return null;
        return message.length() > 1000 ? message.substring(0, 1000) : message;
    }
}
