-- 每一轮远程生成的独立提交事实。
-- provider_request_id 必须先落库再调供应商；当前 provider 尚未消费它，
-- 因此响应丢失时只能停在 SUBMIT_UNKNOWN，不能盲目重投。

ALTER TABLE video_task
    ADD COLUMN current_attempt_id BIGINT NULL COMMENT '当前远程生成 attempt' AFTER provider_task_id,
    ADD COLUMN phase VARCHAR(32) NULL COMMENT '内部阶段；旧行允许为空' AFTER status,
    ADD COLUMN megapixels DOUBLE NULL COMMENT '提交时分辨率快照，Worker 重建 GenerateCommand 用' AFTER ratio,
    ADD KEY idx_video_task_current_attempt (current_attempt_id),
    ADD KEY idx_video_task_phase (status, phase, update_time, id);

CREATE TABLE generation_attempt (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    video_task_id       BIGINT       NOT NULL,
    attempt_no          INT          NOT NULL COMMENT '远程生成轮次，从 1 开始',
    provider             VARCHAR(32)  NOT NULL,
    provider_request_id VARCHAR(64)  NOT NULL COMMENT '调供应商前已持久化的稳定关联键',
    requested_node_id   VARCHAR(64)  NULL COMMENT '管理员试跑指定节点',
    provider_task_id    VARCHAR(128) NULL COMMENT '供应商确认接单后返回的任务 ID',
    node_id              VARCHAR(64)  NULL COMMENT '实际承载该轮任务的节点',
    status               VARCHAR(24)  NOT NULL DEFAULT 'PENDING'
        COMMENT 'PENDING/SUBMITTING/SUBMITTED/SUBMIT_UNKNOWN/FAILED',
    last_error           VARCHAR(1000) NULL,
    create_time          DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time          DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_generation_attempt_round (video_task_id, attempt_no),
    UNIQUE KEY uk_generation_attempt_request (provider_request_id),
    UNIQUE KEY uk_generation_attempt_remote (provider, provider_task_id),
    KEY idx_generation_attempt_status (status, update_time, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='远程生成提交尝试';
