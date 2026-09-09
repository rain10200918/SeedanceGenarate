package org.example.seedancegenarate.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.example.seedancegenarate.entity.AsyncJob;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface AsyncJobMapper extends BaseMapper<AsyncJob> {

    /**
     * 单语句幂等入队，避免并发 {@code INSERT IGNORE -> UPDATE} 的共享锁升级死锁：
     * <ul>
     *   <li>不存在 → 插入 READY；</li>
     *   <li>已活跃（READY/RUNNING）→ 所有业务列保持不变；</li>
     *   <li>已终态（SUCCEEDED/DEAD）→ 原子重置为 READY。</li>
     * </ul>
     * duplicate 分支通过连接级 LAST_INSERT_ID 传回结果：终态重开传原 id，活跃 no-op 传 0；
     * 新插入则由 MySQL 自增列自然设置 id。后续必须在同一事务连接上调用 {@link #selectLastUpsertId()}。
     * status 故意最后赋值，前面的条件全部读取重置前状态。
     */
    @Insert("INSERT INTO async_job(job_type, biz_key, payload, status, attempts, max_attempts, available_at) "
            + "VALUES(#{jobType}, #{bizKey}, #{payload}, 'READY', 0, #{maxAttempts}, NOW()) "
            + "ON DUPLICATE KEY UPDATE "
            + "id = LAST_INSERT_ID(IF(status IN ('SUCCEEDED','DEAD'), id, 0)) "
            + "+ IF(status IN ('SUCCEEDED','DEAD'), 0, id), "
            + "payload = IF(status IN ('SUCCEEDED','DEAD'), VALUES(payload), payload), "
            + "attempts = IF(status IN ('SUCCEEDED','DEAD'), 0, attempts), "
            + "max_attempts = IF(status IN ('SUCCEEDED','DEAD'), VALUES(max_attempts), max_attempts), "
            + "available_at = IF(status IN ('SUCCEEDED','DEAD'), NOW(), available_at), "
            + "lease_owner = IF(status IN ('SUCCEEDED','DEAD'), NULL, lease_owner), "
            + "lease_token = IF(status IN ('SUCCEEDED','DEAD'), NULL, lease_token), "
            + "lease_until = IF(status IN ('SUCCEEDED','DEAD'), NULL, lease_until), "
            + "last_error = IF(status IN ('SUCCEEDED','DEAD'), NULL, last_error), "
            + "status = IF(status IN ('SUCCEEDED','DEAD'), 'READY', status)")
    int upsertReady(@Param("jobType") String jobType,
                    @Param("bizKey") String bizKey,
                    @Param("payload") String payload,
                    @Param("maxAttempts") int maxAttempts);

    /**
     * 延迟入队：新作业 available_at = NOW() + delaySeconds（到期才可领取，替代 RabbitMQ 延迟消息）；
     * 重复入队/终态重置语义与 {@link #upsertReady} 一致。
     */
    @Insert("INSERT INTO async_job(job_type, biz_key, payload, status, attempts, max_attempts, available_at) "
            + "VALUES(#{jobType}, #{bizKey}, #{payload}, 'READY', 0, #{maxAttempts}, "
            + "DATE_ADD(NOW(), INTERVAL #{delaySeconds} SECOND)) "
            + "ON DUPLICATE KEY UPDATE "
            + "id = LAST_INSERT_ID(IF(status IN ('SUCCEEDED','DEAD'), id, 0)) "
            + "+ IF(status IN ('SUCCEEDED','DEAD'), 0, id), "
            + "payload = IF(status IN ('SUCCEEDED','DEAD'), VALUES(payload), payload), "
            + "attempts = IF(status IN ('SUCCEEDED','DEAD'), 0, attempts), "
            + "max_attempts = IF(status IN ('SUCCEEDED','DEAD'), VALUES(max_attempts), max_attempts), "
            + "available_at = IF(status IN ('SUCCEEDED','DEAD'), "
            + "DATE_ADD(NOW(), INTERVAL #{delaySeconds} SECOND), available_at), "
            + "lease_owner = IF(status IN ('SUCCEEDED','DEAD'), NULL, lease_owner), "
            + "lease_token = IF(status IN ('SUCCEEDED','DEAD'), NULL, lease_token), "
            + "lease_until = IF(status IN ('SUCCEEDED','DEAD'), NULL, lease_until), "
            + "last_error = IF(status IN ('SUCCEEDED','DEAD'), NULL, last_error), "
            + "status = IF(status IN ('SUCCEEDED','DEAD'), 'READY', status)")
    int upsertReadyDelayed(@Param("jobType") String jobType,
                           @Param("bizKey") String bizKey,
                           @Param("payload") String payload,
                           @Param("maxAttempts") int maxAttempts,
                           @Param("delaySeconds") long delaySeconds);

    /** 读取本连接上一次 upsert 的信号；必须紧跟 upsert 并处于同一事务。 */
    @Select("SELECT LAST_INSERT_ID()")
    long selectLastUpsertId();

    /** 锁定本批可接管的过期作业；调用者须在同一事务内完成 claim。 */
    @Select("SELECT * FROM async_job WHERE job_type = #{jobType} "
            + "AND status = 'RUNNING' AND lease_until IS NOT NULL AND lease_until <= NOW() "
            + "ORDER BY lease_until ASC, id ASC LIMIT #{limit} FOR UPDATE SKIP LOCKED")
    List<AsyncJob> selectExpiredForClaim(@Param("jobType") String jobType,
                                         @Param("limit") int limit);

    /** 锁定本批可领取的新作业；调用者须在同一事务内完成 claim。 */
    @Select("SELECT * FROM async_job WHERE job_type = #{jobType} "
            + "AND status = 'READY' AND available_at <= NOW() "
            + "ORDER BY available_at ASC, id ASC LIMIT #{limit} FOR UPDATE SKIP LOCKED")
    List<AsyncJob> selectReadyForClaim(@Param("jobType") String jobType,
                                       @Param("limit") int limit);

    /** 用 MySQL 时间建租约，每次接管单调递增 generation。 */
    @Update("UPDATE async_job SET status = 'RUNNING', lease_owner = #{owner}, lease_token = #{token}, "
            + "lease_generation = lease_generation + 1, "
            + "lease_until = TIMESTAMPADD(SECOND, #{leaseSeconds}, NOW()) "
            + "WHERE id = #{id} AND ((status = 'READY' AND available_at <= NOW()) "
            + "OR (status = 'RUNNING' AND lease_until IS NOT NULL AND lease_until <= NOW()))")
    int claim(@Param("id") Long id,
              @Param("owner") String owner,
              @Param("token") String token,
              @Param("leaseSeconds") long leaseSeconds);

    /** 续租；过期但尚未被接管的 Worker 仍可续租，generation 变更后则失败。 */
    @Update("UPDATE async_job SET lease_until = TIMESTAMPADD(SECOND, #{leaseSeconds}, NOW()) "
            + "WHERE id = #{id} AND status = 'RUNNING' AND lease_token = #{token} "
            + "AND lease_generation = #{generation}")
    int renew(@Param("id") Long id,
              @Param("token") String token,
              @Param("generation") long generation,
              @Param("leaseSeconds") long leaseSeconds);

    /** 完成：token + generation 同时匹配才可以收掉作业。 */
    @Update("UPDATE async_job SET status = 'SUCCEEDED', lease_owner = NULL, lease_token = NULL, "
            + "lease_until = NULL, last_error = NULL "
            + "WHERE id = #{id} AND status = 'RUNNING' AND lease_token = #{token} "
            + "AND lease_generation = #{generation}")
    int complete(@Param("id") Long id,
                 @Param("token") String token,
                 @Param("generation") long generation);

    /** 失败重试：次数、状态和 DB 退避时间在同一条 fenced UPDATE 内完成。 */
    @Update("UPDATE async_job SET "
            + "status = IF(attempts + 1 >= max_attempts, 'DEAD', 'READY'), "
            + "available_at = IF(attempts + 1 >= max_attempts, NOW(), "
            + "TIMESTAMPADD(SECOND, #{backoffSeconds}, NOW())), "
            + "attempts = attempts + 1, lease_owner = NULL, lease_token = NULL, "
            + "lease_until = NULL, last_error = #{lastError} "
            + "WHERE id = #{id} AND status = 'RUNNING' AND lease_token = #{token} "
            + "AND lease_generation = #{generation}")
    int failAndRetry(@Param("id") Long id,
                     @Param("token") String token,
                     @Param("generation") long generation,
                     @Param("backoffSeconds") long backoffSeconds,
                     @Param("lastError") String lastError);

    /** 清理过期终态作业（SUCCEEDED/DEAD）：防止辅助表单表数据量无上限膨胀 */
    @org.apache.ibatis.annotations.Delete("DELETE FROM async_job WHERE status IN ('SUCCEEDED', 'DEAD') AND updated_at < #{cutoff} LIMIT #{limit}")
    int deleteExpiredJobs(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);
}
