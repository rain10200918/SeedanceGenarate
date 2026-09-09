CREATE TABLE agent_model_recovery (
    turn_id VARCHAR(64) NOT NULL,
    execution_epoch BIGINT NOT NULL,
    step_no INT NOT NULL,
    phase VARCHAR(16) NOT NULL,
    call_id VARCHAR(64) NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    expected_job_key VARCHAR(160) NOT NULL,
    workspace_version BIGINT NOT NULL,
    recipe_json JSON NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'RUNNING',
    next_retry_at DATETIME NULL,
    error_code VARCHAR(64) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (turn_id, execution_epoch, step_no, phase)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
