ALTER TABLE video_task
  ADD COLUMN moderation_status VARCHAR(24) NOT NULL DEFAULT 'VISIBLE'
    COMMENT '内容可见性：VISIBLE/BLOCKED' AFTER output_type,
  ADD COLUMN moderation_reason_code VARCHAR(32) NULL
    COMMENT '屏蔽原因代码' AFTER moderation_status,
  ADD COLUMN moderation_message VARCHAR(255) NULL
    COMMENT '用户可见的屏蔽说明' AFTER moderation_reason_code,
  ADD COLUMN moderated_by BIGINT NULL
    COMMENT '最后操作管理员 app_user.id' AFTER moderation_message,
  ADD COLUMN moderated_at DATETIME NULL
    COMMENT '最后审核时间' AFTER moderated_by,
  ADD COLUMN moderation_version INT NOT NULL DEFAULT 0
    COMMENT '管理员并发操作乐观锁' AFTER moderated_at,
  ADD KEY idx_video_task_moderation_time (moderation_status, create_time);

CREATE TABLE content_moderation_action (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  video_task_id BIGINT NOT NULL COMMENT 'video_task.id',
  action VARCHAR(16) NOT NULL COMMENT 'BLOCK/RESTORE',
  from_status VARCHAR(24) NOT NULL,
  to_status VARCHAR(24) NOT NULL,
  reason_code VARCHAR(32) NULL,
  user_message VARCHAR(255) NULL,
  internal_note TEXT NULL,
  operator_id BIGINT NOT NULL COMMENT '管理员 app_user.id',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_moderation_action_task_time (video_task_id, id),
  KEY idx_moderation_action_operator_time (operator_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='生成内容屏蔽/恢复不可覆盖审计日志';
