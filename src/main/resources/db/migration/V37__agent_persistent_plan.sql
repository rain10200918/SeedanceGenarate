-- Explicit adoption only: never auto-start or backfill legacy workspace plans.
CREATE TABLE agent_plan (
    id VARCHAR(64) PRIMARY KEY,
    session_id VARCHAR(64) NOT NULL,
    artifact_id VARCHAR(64) NOT NULL,
    artifact_version INT NOT NULL,
    turn_id VARCHAR(64) NULL,
    execution_epoch BIGINT NULL,
    status VARCHAR(32) NOT NULL,
    reason VARCHAR(256) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_agent_plan_version(session_id,artifact_id,artifact_version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE agent_plan_step (
    id VARCHAR(64) PRIMARY KEY,
    plan_id VARCHAR(64) NOT NULL,
    step_key VARCHAR(64) NOT NULL,
    ordinal_no INT NOT NULL,
    kind VARCHAR(32) NOT NULL,
    title VARCHAR(128) NOT NULL,
    depends_on JSON NOT NULL,
    status VARCHAR(32) NOT NULL,
    skill_id VARCHAR(64) NULL,
    skill_call_id VARCHAR(64) NULL,
    input_json JSON NULL,
    result_ref JSON NULL,
    error_code VARCHAR(64) NULL,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_agent_plan_step(plan_id,step_key),
    UNIQUE KEY uk_agent_plan_order(plan_id,ordinal_no),
    UNIQUE KEY uk_agent_step_call(skill_call_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE agent_observation (
    id VARCHAR(64) PRIMARY KEY,
    turn_id VARCHAR(64) NOT NULL,
    execution_epoch BIGINT NOT NULL,
    decision_seq INT NOT NULL,
    skill_call_id VARCHAR(64) NULL,
    type VARCHAR(32) NOT NULL,
    code VARCHAR(64) NOT NULL,
    detail VARCHAR(512) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_agent_observation(turn_id,execution_epoch,decision_seq,type),
    KEY idx_agent_observation_turn(turn_id,execution_epoch,decision_seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE agent_turn ADD COLUMN resume_count INT NOT NULL DEFAULT 0;
ALTER TABLE agent_turn ADD COLUMN budget_start INT NOT NULL DEFAULT 0;
