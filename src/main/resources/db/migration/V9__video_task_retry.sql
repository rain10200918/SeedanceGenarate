-- 任务超时自动处理：retry_count 记录超时重试次数，last_attempt_at 记录本轮尝试起点。
-- 超时判定基准 = last_attempt_at（首次提交时等于 create_time；重试后更新为 now），
-- 避免"重试后任务立刻再次超时"。历史数据处理：last_attempt_at = create_time。

ALTER TABLE video_task
    ADD COLUMN retry_count INT NOT NULL DEFAULT 0 COMMENT '超时自动重试次数（ON_SUCCESS 计费引擎免费重跑）' AFTER node_id,
    ADD COLUMN last_attempt_at DATETIME NULL COMMENT '本轮尝试起点（首次=create_time，重试后=now）' AFTER retry_count;

-- 历史数据初始化：超时判定从此按 last_attempt_at 走
UPDATE video_task SET last_attempt_at = create_time WHERE last_attempt_at IS NULL;

CREATE INDEX idx_video_task_stall ON video_task (status, last_attempt_at);
