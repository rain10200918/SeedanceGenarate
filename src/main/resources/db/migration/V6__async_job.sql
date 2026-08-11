-- 持久化异步作业表：流水线节点提交等「可重试、可领取」的任务。
-- 行级租约（lease_owner/lease_token/lease_until）保证多 Worker 并发领取时
-- 同一作业同一时刻只被一个 Worker 处理；实例崩溃后租约过期即可被接管。

CREATE TABLE async_job (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    job_type     VARCHAR(64)  NOT NULL COMMENT '作业类型，如 PIPELINE_NODE_SUBMIT',
    biz_key      VARCHAR(191) NOT NULL COMMENT '业务幂等键，如 pipeline:{id}:node:{id}',
    payload      VARCHAR(1000) NULL COMMENT '轻量参数，如 {"pipelineNodeId":123}',
    status       VARCHAR(16)  NOT NULL DEFAULT 'READY' COMMENT 'READY/RUNNING/SUCCEEDED/DEAD',
    attempts     INT          NOT NULL DEFAULT 0,
    max_attempts INT          NOT NULL DEFAULT 5,
    available_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '退避后的下次可领取时间',
    lease_owner  VARCHAR(128) NULL,
    lease_token  VARCHAR(64)  NULL,
    lease_until  DATETIME     NULL,
    last_error   VARCHAR(1000) NULL,
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_job_type_biz (job_type, biz_key),
    KEY idx_job_claim (status, available_at, id),
    KEY idx_job_lease (status, lease_until)
) COMMENT '持久化异步作业';
