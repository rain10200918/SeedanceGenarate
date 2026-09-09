-- 新 Agent 与旧三气泡创作隔离；所有执行身份在 MySQL，Redis 只负责门铃。
ALTER TABLE conversation ADD COLUMN creation_mode VARCHAR(16) NOT NULL DEFAULT 'LEGACY';
ALTER TABLE conversation_message ADD COLUMN parts JSON NULL;
ALTER TABLE conversation_message ADD COLUMN agent_turn_id VARCHAR(64) NULL;
ALTER TABLE conversation_message ADD COLUMN agent_request_hash VARCHAR(64) NULL;
ALTER TABLE prompt_token_usage ADD COLUMN agent_turn_id VARCHAR(64) NULL;

CREATE TABLE agent_session (
    id VARCHAR(64) PRIMARY KEY,
    conversation_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    revision BIGINT NOT NULL DEFAULT 0,
    goal TEXT NULL,
    summary TEXT NULL,
    active_turn_id VARCHAR(64) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_agent_conversation (conversation_id),
    KEY idx_agent_owner (user_id, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE agent_turn (
    id VARCHAR(64) PRIMARY KEY,
    session_id VARCHAR(64) NOT NULL,
    channel VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL,
    step_no INT NOT NULL DEFAULT 0,
    epoch BIGINT NOT NULL DEFAULT 1,
    error_message VARCHAR(256) NULL,
    deadline_at DATETIME NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_agent_turn_session (session_id, created_at),
    KEY idx_agent_turn_status (status, deadline_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE agent_decision (
    id VARCHAR(64) PRIMARY KEY,
    turn_id VARCHAR(64) NOT NULL,
    epoch BIGINT NOT NULL,
    step_no INT NOT NULL,
    payload JSON NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_agent_decision_step (turn_id, epoch, step_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE agent_skill_call (
    id VARCHAR(64) PRIMARY KEY,
    turn_id VARCHAR(64) NOT NULL,
    skill_id VARCHAR(64) NOT NULL,
    skill_version VARCHAR(32) NOT NULL,
    input_json JSON NOT NULL,
    status VARCHAR(32) NOT NULL,
    epoch BIGINT NOT NULL,
    step_no INT NOT NULL,
    error_message VARCHAR(256) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_agent_call_step (turn_id, epoch, step_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE agent_interaction (
    id VARCHAR(64) PRIMARY KEY,
    turn_id VARCHAR(64) NOT NULL,
    epoch BIGINT NOT NULL,
    version INT NOT NULL DEFAULT 1,
    status VARCHAR(32) NOT NULL,
    question VARCHAR(4000) NOT NULL,
    options_json JSON NOT NULL,
    response_text VARCHAR(4000) NULL,
    expires_at DATETIME NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_agent_interaction_turn (turn_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE agent_artifact_version (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    artifact_id VARCHAR(64) NOT NULL,
    version_no INT NOT NULL,
    session_id VARCHAR(64) NOT NULL,
    user_id BIGINT NOT NULL,
    type VARCHAR(16) NOT NULL,
    title VARCHAR(128) NOT NULL,
    content MEDIUMTEXT NOT NULL,
    source_call_id VARCHAR(64) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_agent_artifact_version (artifact_id, version_no),
    UNIQUE KEY uk_agent_artifact_call (source_call_id),
    KEY idx_agent_artifact_session (session_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
