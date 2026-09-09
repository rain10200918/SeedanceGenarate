-- 不回填历史零值：旧数据无法区分真实零消耗与未获得 usage。
ALTER TABLE prompt_token_usage
    MODIFY prompt_tokens INT NULL DEFAULT NULL COMMENT '输入token，未知为NULL',
    MODIFY completion_tokens INT NULL DEFAULT NULL COMMENT '输出token，未知为NULL',
    MODIFY total_tokens INT NULL DEFAULT NULL COMMENT '输入输出均已知时合计',
    ADD COLUMN decision_step INT NULL COMMENT 'Agent决策步骤',
    ADD COLUMN usage_source VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN' COMMENT 'PROVIDER/ESTIMATED/UNKNOWN';
