package org.example.seedancegenarate.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.example.seedancegenarate.entity.AsyncJob;

import java.time.LocalDateTime;

@Mapper
public interface AsyncJobMapper extends BaseMapper<AsyncJob> {

    /**
     * 入队（upsert）：
     * <ul>
     *   <li>不存在 → 插入 READY；</li>
     *   <li>已活跃（READY/RUNNING）→ 不动（保留租约，防止重复入队打断在途处理）；</li>
     *   <li>已终态（SUCCEEDED/DEAD）→ 重置为 READY（重新 run 重新执行）。</li>
     * </ul>
     */
    @Insert("INSERT INTO async_job(job_type, biz_key, payload, status, attempts, max_attempts, available_at) "
            + "VALUES(#{jobType}, #{bizKey}, #{payload}, 'READY', 0, #{maxAttempts}, NOW()) "
            + "ON DUPLICATE KEY UPDATE "
            + "status = IF(status IN ('READY','RUNNING'), status, 'READY'), "
            + "attempts = IF(status IN ('READY','RUNNING'), attempts, 0), "
            + "available_at = IF(status IN ('READY','RUNNING'), available_at, NOW()), "
            + "lease_owner = IF(status IN ('READY','RUNNING'), lease_owner, NULL), "
            + "lease_token = IF(status IN ('READY','RUNNING'), lease_token, NULL), "
            + "lease_until = IF(status IN ('READY','RUNNING'), lease_until, NULL), "
            + "last_error = IF(status IN ('READY','RUNNING'), last_error, NULL), "
            + "payload = VALUES(payload)")
    int enqueueUpsert(@Param("jobType") String jobType,
                      @Param("bizKey") String bizKey,
                      @Param("payload") String payload,
                      @Param("maxAttempts") int maxAttempts);

    /**
     * 延迟入队：新作业 available_at = NOW() + delaySeconds（到期才可领取，替代 RabbitMQ 延迟消息）；
     * 重复入队/终态重置语义与 {@link #enqueueUpsert} 一致（活跃作业保留原 available_at 与租约）。
     */
    @Insert("INSERT INTO async_job(job_type, biz_key, payload, status, attempts, max_attempts, available_at) "
            + "VALUES(#{jobType}, #{bizKey}, #{payload}, 'READY', 0, #{maxAttempts}, "
            + "DATE_ADD(NOW(), INTERVAL #{delaySeconds} SECOND)) "
            + "ON DUPLICATE KEY UPDATE "
            + "status = IF(status IN ('READY','RUNNING'), status, 'READY'), "
            + "attempts = IF(status IN ('READY','RUNNING'), attempts, 0), "
            + "available_at = IF(status IN ('READY','RUNNING'), available_at, NOW()), "
            + "lease_owner = IF(status IN ('READY','RUNNING'), lease_owner, NULL), "
            + "lease_token = IF(status IN ('READY','RUNNING'), lease_token, NULL), "
            + "lease_until = IF(status IN ('READY','RUNNING'), lease_until, NULL), "
            + "last_error = IF(status IN ('READY','RUNNING'), last_error, NULL), "
            + "payload = VALUES(payload)")
    int enqueueDelayed(@Param("jobType") String jobType,
                       @Param("bizKey") String bizKey,
                       @Param("payload") String payload,
                       @Param("maxAttempts") int maxAttempts,
                       @Param("delaySeconds") long delaySeconds);

    /** 行级租约领取：只有影响 1 行才算抢到，天然防止两个 Worker 处理同一作业。 */
    @Update("UPDATE async_job SET status = 'RUNNING', lease_owner = #{owner}, lease_token = #{token}, "
            + "lease_until = #{leaseUntil} "
            + "WHERE id = #{id} AND status = 'READY' AND available_at <= #{now} "
            + "AND (lease_until IS NULL OR lease_until < #{now})")
    int claim(@Param("id") Long id,
              @Param("owner") String owner,
              @Param("token") String token,
              @Param("now") LocalDateTime now,
              @Param("leaseUntil") LocalDateTime leaseUntil);

    /** 完成：只有持有租约的 Worker 能标记成功。 */
    @Update("UPDATE async_job SET status = 'SUCCEEDED', lease_owner = NULL, lease_token = NULL, "
            + "lease_until = NULL, last_error = NULL "
            + "WHERE id = #{id} AND lease_token = #{token}")
    int complete(@Param("id") Long id, @Param("token") String token);

    /** 失败重试：持有租约的 Worker 将作业退回 READY，按退避设置 available_at；超过次数进入 DEAD。 */
    @Update("UPDATE async_job SET status = #{status}, attempts = attempts + 1, "
            + "available_at = #{availableAt}, lease_owner = NULL, lease_token = NULL, "
            + "lease_until = NULL, last_error = #{lastError} "
            + "WHERE id = #{id} AND lease_token = #{token}")
    int failAndRetry(@Param("id") Long id,
                     @Param("token") String token,
                     @Param("status") String status,
                     @Param("availableAt") LocalDateTime availableAt,
                     @Param("lastError") String lastError);

    /** 清理过期终态作业（SUCCEEDED/DEAD）：防止辅助表单表数据量无上限膨胀 */
    @org.apache.ibatis.annotations.Delete("DELETE FROM async_job WHERE status IN ('SUCCEEDED', 'DEAD') AND updated_at < #{cutoff} LIMIT #{limit}")
    int deleteExpiredJobs(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);
}
