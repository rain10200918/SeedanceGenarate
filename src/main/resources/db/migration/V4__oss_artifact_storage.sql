-- Store generated outputs in OSS instead of an application instance's local data/videos directory.
-- video_url remains as a public media-route token during migration; artifact_key is the OSS source of truth.

ALTER TABLE video_task
  ADD COLUMN artifact_storage_type VARCHAR(16) NULL COMMENT '产物存储类型；当前为 OSS' AFTER video_url,
  ADD COLUMN artifact_key VARCHAR(512) NULL COMMENT 'OSS 产物 object key' AFTER artifact_storage_type,
  ADD COLUMN artifact_content_type VARCHAR(128) NULL COMMENT '产物 MIME 类型' AFTER artifact_key,
  ADD COLUMN artifact_size BIGINT NULL COMMENT '产物字节数' AFTER artifact_content_type,
  ADD COLUMN artifact_etag VARCHAR(128) NULL COMMENT 'OSS ETag' AFTER artifact_size,
  ADD KEY idx_video_task_artifact_key (artifact_key);
