CREATE TABLE agent_local_repair (
    id VARCHAR(64) PRIMARY KEY,
    session_id VARCHAR(64) NOT NULL,
    plan_id VARCHAR(64) NOT NULL,
    workspace_version BIGINT NOT NULL,
    failure_id VARCHAR(128) NOT NULL,
    request_key VARCHAR(64) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    binding_json JSON NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_agent_repair_request(session_id,request_key),
    UNIQUE KEY uk_agent_repair_failure(session_id,failure_id),
    KEY idx_agent_repair_plan(session_id,plan_id,workspace_version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
