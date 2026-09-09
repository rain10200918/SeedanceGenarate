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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** MySQL 作业表实现：biz_key 唯一幂等入队，行级租约领取。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AsyncJobServiceImpl implements AsyncJobService {
    private static final int MAX_BATCH_SIZE = 100;
    private static final long MAX_LEASE_SECONDS = 86_400;
    private static final int MAX_JOB_TYPE_LENGTH = 64;
    private static final int MAX_BIZ_KEY_LENGTH = 191;
    private static final int MAX_PAYLOAD_BYTES = 32 * 1024;
    private static final long MAX_DELAY_SECONDS = 86_400;

    private final AsyncJobMapper asyncJobMapper;
    private final AsyncJobProperties properties;
    private final JobAvailableNotifier jobAvailableNotifier;
    /** 一个进程生命周期内稳定，不随每次 claim 变化。 */
    private final String leaseOwner = createLeaseOwner();
    /** 单槽领取时按 job type 交替 READY/过期 RUNNING，避免任一队列长期饥饿。 */
    private final ConcurrentHashMap<String, AtomicBoolean> singleClaimExpiredFirst = new ConcurrentHashMap<>();

    @Override
    @Transactional
    public void enqueue(String jobType, String bizKey, String payload) {
        if (!StringUtils.hasText(jobType) || !StringUtils.hasText(bizKey)) {
            return;
        }
        // 只有「实际入队或重置」才通知；active duplicate 由同连接 LAST_INSERT_ID(0)
        // 显式标记为 no-op，避免依赖 Connector/J affected-row 模式而反复刷频道。
        String type = jobType.trim();
        String key = bizKey.trim();
        requireEnqueueFields(type, key, payload);
        int maxAttempts = Math.max(properties.getMaxAttempts(), 1);
        asyncJobMapper.upsertReady(type, key, payload, maxAttempts);
        if (asyncJobMapper.selectLastUpsertId() > 0) {
            notifyAfterCommit(type);
        }
    }

    @Override
    @Transactional
    public void enqueueDelayed(String jobType, String bizKey, String payload, long delaySeconds) {
        if (!StringUtils.hasText(jobType) || !StringUtils.hasText(bizKey)) {
            return;
        }
        String type = jobType.trim();
        String key = bizKey.trim();
        requireEnqueueFields(type, key, payload);
        if (delaySeconds < 0 || delaySeconds > MAX_DELAY_SECONDS) {
            throw new IllegalArgumentException("delaySeconds must be between 0 and 86400");
        }
        int maxAttempts = Math.max(properties.getMaxAttempts(), 1);
        long delay = delaySeconds;
        asyncJobMapper.upsertReadyDelayed(type, key, payload, maxAttempts, delay);
        if (asyncJobMapper.selectLastUpsertId() > 0) {
            notifyAfterCommit(type, delay);
        }
    }

    /** 未提交的 job 对其他实例不可见，通知必须跟随事务提交；无事务调用保持即时唤醒。 */
    private void notifyAfterCommit(String jobType) {
        afterCommit(() -> jobAvailableNotifier.notify(jobType));
    }

    private void notifyAfterCommit(String jobType, long delaySeconds) {
        afterCommit(() -> jobAvailableNotifier.notifyAfterDelay(jobType, delaySeconds));
    }

    private void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
            return;
        }
        action.run();
    }

    @Override
    @Transactional
    public List<AsyncJob> claimBatch(String jobType, int batchSize, long leaseSeconds) {
        String normalizedType = requireText(jobType, "jobType");
        int boundedBatchSize = requireBatchSize(batchSize);
        long boundedLeaseSeconds = requireLeaseSeconds(leaseSeconds);

        List<AsyncJob> candidates = new ArrayList<>(boundedBatchSize);
        if (boundedBatchSize == 1) {
            claimSingleCandidate(normalizedType, candidates);
        } else {
            // 过期接管只占有界份额，避免崩溃恢复与新作业任一方饿死。
            int expiredLimit = Math.max(1, boundedBatchSize / 4);
            candidates.addAll(asyncJobMapper.selectExpiredForClaim(normalizedType, expiredLimit));
            int readyLimit = boundedBatchSize - candidates.size();
            if (readyLimit > 0) {
                candidates.addAll(asyncJobMapper.selectReadyForClaim(normalizedType, readyLimit));
            }
        }

        List<AsyncJob> claimed = new ArrayList<>();
        for (AsyncJob candidate : candidates) {
            String token = UUID.randomUUID().toString();
            int rows = asyncJobMapper.claim(candidate.getId(), leaseOwner, token, boundedLeaseSeconds);
            if (rows == 1) {
                long previousGeneration = candidate.getLeaseGeneration() == null
                        ? 0 : candidate.getLeaseGeneration();
                candidate.setStatus(AsyncJob.STATUS_RUNNING);
                candidate.setLeaseOwner(leaseOwner);
                candidate.setLeaseToken(token);
                candidate.setLeaseGeneration(previousGeneration + 1);
                claimed.add(candidate);
            }
            // 影响 0 行说明条件已变，不把候选行伪装成已领取。
        }
        return claimed;
    }

    private void claimSingleCandidate(String jobType, List<AsyncJob> candidates) {
        AtomicBoolean preference = singleClaimExpiredFirst.computeIfAbsent(
                jobType, ignored -> new AtomicBoolean(true));
        boolean expiredFirst;
        do {
            expiredFirst = preference.get();
        } while (!preference.compareAndSet(expiredFirst, !expiredFirst));
        if (expiredFirst) {
            candidates.addAll(asyncJobMapper.selectExpiredForClaim(jobType, 1));
            if (candidates.isEmpty()) {
                candidates.addAll(asyncJobMapper.selectReadyForClaim(jobType, 1));
            }
        } else {
            candidates.addAll(asyncJobMapper.selectReadyForClaim(jobType, 1));
            if (candidates.isEmpty()) {
                candidates.addAll(asyncJobMapper.selectExpiredForClaim(jobType, 1));
            }
        }
    }

    @Override
    public AsyncJob find(String jobType, String bizKey) {
        return asyncJobMapper.selectOne(Wrappers.<AsyncJob>lambdaQuery()
                .eq(AsyncJob::getJobType, jobType)
                .eq(AsyncJob::getBizKey, bizKey)
                .last("limit 1"));
    }

    @Override
    public boolean renew(AsyncJob lease, long leaseSeconds) {
        requireLease(lease);
        long boundedLeaseSeconds = requireLeaseSeconds(leaseSeconds);
        return asyncJobMapper.renew(lease.getId(), lease.getLeaseToken(),
                lease.getLeaseGeneration(), boundedLeaseSeconds) == 1;
    }

    @Override
    public boolean complete(AsyncJob lease) {
        requireLease(lease);
        return asyncJobMapper.complete(lease.getId(), lease.getLeaseToken(),
                lease.getLeaseGeneration()) == 1;
    }

    @Override
    public boolean failAndRetry(AsyncJob lease, String error) {
        requireLease(lease);
        int attempts = lease.getAttempts() == null ? 0 : Math.max(lease.getAttempts(), 0);
        long retryDelaySeconds = backoffSeconds(attempts);
        boolean updated = asyncJobMapper.failAndRetry(lease.getId(), lease.getLeaseToken(),
                lease.getLeaseGeneration(), retryDelaySeconds, truncate(error)) == 1;
        int maxAttempts = lease.getMaxAttempts() == null
                ? Math.max(properties.getMaxAttempts(), 1) : lease.getMaxAttempts();
        if (updated && attempts + 1 >= maxAttempts) {
            log.warn("作业超过重试上限进入 DEAD: jobId={}", lease.getId());
        } else if (updated) {
            notifyAfterCommit(lease.getJobType(), retryDelaySeconds);
        }
        return updated;
    }

    private long backoffSeconds(int attempts) {
        long base = Math.min(Math.max(properties.getBackoffBaseSeconds(), 1), 3600);
        return Math.min(base * (1L << Math.min(attempts, 10)), 3600);
    }

    private void requireLease(AsyncJob lease) {
        if (lease == null || lease.getId() == null || lease.getId() <= 0
                || !StringUtils.hasText(lease.getLeaseToken())
                || lease.getLeaseGeneration() == null || lease.getLeaseGeneration() < 1) {
            throw new IllegalArgumentException("valid async job lease is required");
        }
    }

    private int requireBatchSize(int batchSize) {
        if (batchSize < 1 || batchSize > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("batchSize must be between 1 and 100");
        }
        return batchSize;
    }

    private long requireLeaseSeconds(long leaseSeconds) {
        if (leaseSeconds < 1 || leaseSeconds > MAX_LEASE_SECONDS) {
            throw new IllegalArgumentException("leaseSeconds must be between 1 and 86400");
        }
        return leaseSeconds;
    }

    private String requireText(String value, String name) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.trim();
    }

    private void requireEnqueueFields(String jobType, String bizKey, String payload) {
        if (jobType.length() > MAX_JOB_TYPE_LENGTH) {
            throw new IllegalArgumentException("jobType must not exceed 64 characters");
        }
        if (bizKey.length() > MAX_BIZ_KEY_LENGTH) {
            throw new IllegalArgumentException("bizKey must not exceed 191 characters");
        }
        if (payload != null && payload.getBytes(StandardCharsets.UTF_8).length > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("payload must not exceed 32768 UTF-8 bytes");
        }
    }

    private static String createLeaseOwner() {
        String host = System.getenv("HOSTNAME");
        if (!StringUtils.hasText(host)) {
            host = java.net.InetAddress.getLoopbackAddress().getHostName();
        }
        if (host.length() > 80) {
            host = host.substring(0, 80);
        }
        return host + "-" + UUID.randomUUID();
    }

    private String truncate(String message) {
        if (message == null) return null;
        return message.length() > 1000 ? message.substring(0, 1000) : message;
    }
}
