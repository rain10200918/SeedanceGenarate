-- 轮询生产器只按 status + next_poll_at 取到期任务；旧索引把 provider 放在中间，
-- provider 没有等值条件时 next_poll_at 无法继续缩小扫描范围。
ALTER TABLE video_task
    DROP INDEX idx_video_task_poll_due,
    ADD KEY idx_video_task_poll_due (status, next_poll_at, id);

-- finalize URL 单字段限制为 16 KiB，async_job 整体 payload 限制为 32 KiB，均可能超过旧 VARCHAR(1000)。
ALTER TABLE async_job
    MODIFY COLUMN payload TEXT NULL;
