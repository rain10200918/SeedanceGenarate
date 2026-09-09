CREATE TABLE agent_approval (
    id VARCHAR(64) PRIMARY KEY,
    session_id VARCHAR(64) NOT NULL,
    turn_id VARCHAR(64) NOT NULL,
    call_id VARCHAR(64) NOT NULL,
    epoch BIGINT NOT NULL,
    step_no INT NOT NULL,
    version INT NOT NULL DEFAULT 1,
    status VARCHAR(32) NOT NULL,
    quote_json JSON NOT NULL,
    request_id VARCHAR(128) NOT NULL,
    task_id VARCHAR(64) NULL,
    error_message VARCHAR(256) NULL,
    expires_at DATETIME NOT NULL,
    next_check_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_agent_approval_call (call_id),
    UNIQUE KEY uk_agent_approval_request (request_id),
    UNIQUE KEY uk_agent_approval_task (task_id),
    KEY idx_agent_approval_reconcile (status,next_check_at),
    KEY idx_agent_approval_session (session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
ALTER TABLE agent_artifact_version ADD COLUMN task_id VARCHAR(64) NULL;
