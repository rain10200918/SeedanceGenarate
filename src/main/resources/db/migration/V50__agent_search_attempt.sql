CREATE TABLE agent_search_attempt (
    call_id VARCHAR(64) NOT NULL PRIMARY KEY,
    scope_key VARCHAR(160) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    status VARCHAR(32) NOT NULL,
    expected_job_key VARCHAR(160) NOT NULL,
    next_retry_at DATETIME NULL,
    error_code VARCHAR(64) NULL,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_agent_search_scope (scope_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
