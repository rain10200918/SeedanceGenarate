-- Separate the public business task ID from the provider task ID.
-- Existing task_id values remain untouched as legacy lookup keys so prior API responses,
-- pipeline references, API logs and webhook records continue to resolve during transition.

ALTER TABLE video_task
  ADD COLUMN biz_task_id VARCHAR(128) NULL COMMENT '系统公开任务 ID；创建任务时即生成' AFTER user_id,
  ADD COLUMN provider_task_id VARCHAR(128) NULL COMMENT '提供方任务 ID；提交成功后回写' AFTER task_id;

UPDATE video_task
SET provider_task_id = task_id
WHERE provider_task_id IS NULL
  AND task_id IS NOT NULL
  AND task_id <> '';

UPDATE video_task
SET biz_task_id = CONCAT('tsk_legacy_', id)
WHERE biz_task_id IS NULL
   OR biz_task_id = '';

-- Move internal references to the new business ID. External callers can still use
-- the old task_id because the application lookup remains compatible with both IDs.
UPDATE api_call_log log_row
JOIN video_task task_row ON task_row.task_id = log_row.task_id
SET log_row.task_id = task_row.biz_task_id;

UPDATE webhook_delivery delivery
JOIN video_task task_row ON task_row.task_id = delivery.task_id
SET delivery.task_id = task_row.biz_task_id;

UPDATE pipeline_node node_row
JOIN video_task task_row ON task_row.task_id = node_row.task_id
SET node_row.task_id = task_row.biz_task_id;

UPDATE user_asset asset_row
JOIN video_task task_row ON task_row.task_id = asset_row.task_id
SET asset_row.task_id = task_row.biz_task_id;

ALTER TABLE video_task
  ADD UNIQUE KEY uk_video_task_biz_task_id (biz_task_id),
  ADD KEY idx_video_task_provider_task_id (provider_task_id);
