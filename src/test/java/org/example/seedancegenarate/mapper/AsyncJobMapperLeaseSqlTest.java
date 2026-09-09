package org.example.seedancegenarate.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsyncJobMapperLeaseSqlTest {

    @Test
    // 【测什么】领取候选时间只认 MySQL NOW，且 READY/过期 RUNNING 用 SKIP LOCKED 分开查。
    // 【怎么算红】把时间换成 Java 入参、删掉 SKIP LOCKED，或把两种状态合成 OR 查询，这条必须变红。
    void claimCandidatesUseDatabaseTimeAndSkipLocked() throws Exception {
        String expired = selectSql("selectExpiredForClaim", String.class, int.class);
        String ready = selectSql("selectReadyForClaim", String.class, int.class);

        assertTrue(expired.contains("STATUS = 'RUNNING'"));
        assertTrue(expired.contains("LEASE_UNTIL <= NOW()"));
        assertTrue(expired.contains("FOR UPDATE SKIP LOCKED"));
        assertFalse(expired.contains(" OR "));
        assertTrue(ready.contains("STATUS = 'READY'"));
        assertTrue(ready.contains("AVAILABLE_AT <= NOW()"));
        assertTrue(ready.contains("FOR UPDATE SKIP LOCKED"));
        assertFalse(ready.contains(" OR "));
    }

    @Test
    // 【测什么】claim 在 MySQL 内生成租约到期时间并单调递增 generation。
    // 【怎么算红】恢复传入 Java leaseUntil 或不递增 lease_generation，这条必须变红。
    void claimUsesDatabaseTimeAndAdvancesGeneration() throws Exception {
        String sql = updateSql("claim", Long.class, String.class, String.class, long.class);

        assertTrue(sql.contains("LEASE_GENERATION = LEASE_GENERATION + 1"));
        assertTrue(sql.contains("TIMESTAMPADD(SECOND"));
        assertTrue(sql.contains("NOW()"));
        assertFalse(sql.contains("LEASEUNTIL"));
    }

    @Test
    // 【测什么】renew、complete、fail 都用 RUNNING+token+generation 同一套 fencing。
    // 【怎么算红】任一写入少了 RUNNING、lease_token 或 lease_generation 条件，这条必须变红。
    void everyLeaseMutationUsesTheSameFence() throws Exception {
        String renew = updateSql("renew", Long.class, String.class, long.class, long.class);
        String complete = updateSql("complete", Long.class, String.class, long.class);
        String fail = updateSql("failAndRetry", Long.class, String.class, long.class, long.class, String.class);

        for (String sql : List.of(renew, complete, fail)) {
            assertTrue(sql.contains("STATUS = 'RUNNING'"));
            assertTrue(sql.contains("LEASE_TOKEN = #{TOKEN}"));
            assertTrue(sql.contains("LEASE_GENERATION = #{GENERATION}"));
        }
        assertFalse(complete.contains("LEASE_UNTIL > NOW()"));
        assertFalse(fail.contains("LEASE_UNTIL > NOW()"));
    }

    @Test
    // 【测什么】失败次数、READY/DEAD 选择和 DB 退避时间在同一 UPDATE 内完成。
    // 【怎么算红】把 attempts 改回 Java 先读后算，或 available_at 不再使用 NOW，这条必须变红。
    void failureTransitionIsAtomicAndUsesDatabaseBackoffTime() throws Exception {
        String sql = updateSql("failAndRetry", Long.class, String.class, long.class, long.class, String.class);

        assertTrue(sql.contains("ATTEMPTS = ATTEMPTS + 1"));
        assertTrue(sql.contains("ATTEMPTS + 1 >= MAX_ATTEMPTS"));
        assertTrue(sql.contains("'DEAD'"));
        assertTrue(sql.contains("'READY'"));
        assertTrue(sql.contains("TIMESTAMPADD(SECOND"));
        assertTrue(sql.contains("NOW()"));
    }

    @Test
    void recurringUpsertPreservesActivePayloadAndEvaluatesOldStatusBeforeReset() throws Exception {
        // 【测什么】入队是单条 ODKU，active no-op/终态重开不依赖 Connector/J 影响行模式。
        // 【怎么算红】恢复 INSERT IGNORE→UPDATE 会重现唯一索引 S 锁升级死锁；删 LAST_INSERT_ID 会误发门铃。
        String immediate = insertSql("upsertReady", String.class, String.class, String.class, int.class);
        String delayed = insertSql("upsertReadyDelayed", String.class, String.class, String.class,
                int.class, long.class);

        for (String sql : List.of(immediate, delayed)) {
            assertTrue(sql.contains("ON DUPLICATE KEY UPDATE"), sql);
            assertFalse(sql.contains("INSERT IGNORE"), sql);
            assertTrue(sql.contains("LAST_INSERT_ID(IF(STATUS IN ('SUCCEEDED','DEAD'), ID, 0))"), sql);
            assertTrue(sql.contains("PAYLOAD = IF(STATUS IN ('SUCCEEDED','DEAD'), VALUES(PAYLOAD), PAYLOAD)"), sql);
            assertTrue(sql.contains("STATUS = IF(STATUS IN ('SUCCEEDED','DEAD'), 'READY', STATUS)"), sql);
            assertTrue(sql.lastIndexOf("STATUS = IF") > sql.indexOf("PAYLOAD = IF"), sql);
        }
        assertTrue(delayed.contains("DATE_ADD(NOW(), INTERVAL #{DELAYSECONDS} SECOND)"), delayed);
        assertTrue(selectSql("selectLastUpsertId").contains("LAST_INSERT_ID()"));
    }

    @Test
    // 【测什么】V29 落 generation，复用 V18 的 READY 复合索引并补齐过期 RUNNING 索引。
    // 【怎么算红】重复创建 READY 索引，或删掉 generation/任一索引的关键列顺序，这条必须变红。
    void migrationAddsGenerationAndReusesTheReadyClaimIndex() throws Exception {
        try (InputStream stream = getClass().getResourceAsStream(
                "/db/migration/V29__async_job_fencing.sql")) {
            assertNotNull(stream);
            String sql = normalize(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            assertTrue(sql.contains("ADD COLUMN LEASE_GENERATION BIGINT NOT NULL DEFAULT 0"));
            assertTrue(sql.contains("RENAME INDEX IDX_JOB_CLAIM_V2 TO IDX_JOB_READY"));
            assertTrue(sql.contains("IDX_JOB_EXPIRED (JOB_TYPE, STATUS, LEASE_UNTIL, ID)"));
            assertFalse(sql.contains("ADD KEY IDX_JOB_READY"));
        }
        try (InputStream stream = getClass().getResourceAsStream(
                "/db/migration/V18__perf_indexes.sql")) {
            assertNotNull(stream);
            String sql = normalize(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            assertTrue(sql.contains("IDX_JOB_CLAIM_V2 ON ASYNC_JOB (JOB_TYPE, STATUS, AVAILABLE_AT, ID)"));
        }
    }

    @Test
    void pollDueIndexMatchesTheProducerPredicateOrder() throws Exception {
        // 【测什么】到期扫描索引连续覆盖 status + next_poll_at + id，不被未等值的 provider 截断。
        // 【怎么算红】继续使用旧 (status,provider,next_poll_at) 会随 PROCESSING 任务量放大扫描。
        try (InputStream stream = getClass().getResourceAsStream(
                "/db/migration/V31__video_task_poll_due_index.sql")) {
            assertNotNull(stream);
            String sql = normalize(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            assertTrue(sql.contains("IDX_VIDEO_TASK_POLL_DUE (STATUS, NEXT_POLL_AT, ID)"), sql);
            assertFalse(sql.contains("(STATUS, PROVIDER, NEXT_POLL_AT)"), sql);
            assertTrue(sql.contains("ALTER TABLE ASYNC_JOB MODIFY COLUMN PAYLOAD TEXT NULL"), sql);
        }
    }

    private String selectSql(String name, Class<?>... parameterTypes) throws Exception {
        Method method = AsyncJobMapper.class.getMethod(name, parameterTypes);
        return normalize(String.join(" ", method.getAnnotation(Select.class).value()));
    }

    private String updateSql(String name, Class<?>... parameterTypes) throws Exception {
        Method method = AsyncJobMapper.class.getMethod(name, parameterTypes);
        return normalize(String.join(" ", method.getAnnotation(Update.class).value()));
    }

    private String insertSql(String name, Class<?>... parameterTypes) throws Exception {
        Method method = AsyncJobMapper.class.getMethod(name, parameterTypes);
        return normalize(String.join(" ", method.getAnnotation(Insert.class).value()));
    }

    private String normalize(String sql) {
        return Arrays.stream(sql.trim().split("\\s+"))
                .filter(part -> !part.isBlank())
                .reduce((left, right) -> left + " " + right)
                .orElse("")
                .toUpperCase();
    }
}
