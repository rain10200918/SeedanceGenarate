ALTER TABLE agent_skill_call ADD COLUMN call_slot INT NOT NULL DEFAULT 0;
CREATE UNIQUE INDEX uk_agent_call_slot ON agent_skill_call(turn_id,epoch,step_no,call_slot);
ALTER TABLE agent_skill_call DROP INDEX uk_agent_call_step;
ALTER TABLE agent_approval ADD COLUMN grant_id VARCHAR(64) NULL;
CREATE INDEX idx_agent_approval_grant ON agent_approval(grant_id,status);

CREATE TABLE agent_generation_batch (
    id VARCHAR(64) PRIMARY KEY,
    session_id VARCHAR(64) NOT NULL,
    turn_id VARCHAR(64) NOT NULL,
    epoch BIGINT NOT NULL,
    step_no INT NOT NULL,
    parent_call_id VARCHAR(64) NOT NULL,
    plan_id VARCHAR(64) NOT NULL,
    plan_step_id VARCHAR(64) NOT NULL,
    workspace_version BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    version INT NOT NULL DEFAULT 1,
    binding_hash VARCHAR(64) NOT NULL,
    quote_set_hash VARCHAR(64) NOT NULL,
    output_type VARCHAR(16) NOT NULL,
    total_amount DECIMAL(20,6) NOT NULL,
    currency VARCHAR(16) NOT NULL,
    parallel_limit INT NOT NULL,
    expires_at DATETIME NOT NULL,
    reason VARCHAR(256) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_agent_batch_parent(parent_call_id),
    KEY idx_agent_batch_due(status,updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE agent_batch_item (
    id VARCHAR(64) PRIMARY KEY,
    batch_id VARCHAR(64) NOT NULL,
    scene_id VARCHAR(64) NOT NULL,
    call_id VARCHAR(64) NOT NULL,
    approval_id VARCHAR(64) NOT NULL,
    ordinal_no INT NOT NULL,
    source_ref JSON NOT NULL,
    UNIQUE KEY uk_agent_batch_scene(batch_id,scene_id),
    UNIQUE KEY uk_agent_batch_call(call_id),
    UNIQUE KEY uk_agent_batch_approval(approval_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
