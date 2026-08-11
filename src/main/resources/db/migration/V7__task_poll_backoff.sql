-- 任务轮询退避：next_poll_at 记录下次可轮询时间。
-- POLL 机制引擎（Seedance）按任务年龄退避，避免固定 2 秒无限高频查询；
-- CALLBACK 机制引擎（ComfyUI）不更新该字段，由回调 + 对账兜底驱动。

ALTER TABLE video_task
    ADD COLUMN next_poll_at DATETIME NULL COMMENT '下次可轮询时间（退避）；NULL=立即可查' AFTER error_msg;

CREATE INDEX idx_video_task_poll_due ON video_task (status, provider, next_poll_at);
