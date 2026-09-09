-- Worker 租约 fencing：每次接管单调递增 generation，旧 Worker 不能回写新租约。
ALTER TABLE async_job
    ADD COLUMN lease_generation BIGINT NOT NULL DEFAULT 0 AFTER lease_token,
    DROP INDEX idx_job_claim,
    DROP INDEX idx_job_lease,
    RENAME INDEX idx_job_claim_v2 TO idx_job_ready,
    ADD KEY idx_job_expired (job_type, status, lease_until, id);
