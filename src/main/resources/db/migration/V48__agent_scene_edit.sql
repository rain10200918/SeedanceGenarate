-- Frozen local edit intent and exact legacy approvals; no new task submission path.
CREATE TABLE agent_scene_edit (
    id VARCHAR(64) PRIMARY KEY,
    session_id VARCHAR(64) NOT NULL,
    plan_id VARCHAR(64) NOT NULL,
    old_plan_id VARCHAR(64) NOT NULL,
    edit_step_key VARCHAR(64) NOT NULL,
    reference_json JSON NOT NULL,
    instruction VARCHAR(2000) NOT NULL,
    waits_json JSON NOT NULL,
    status VARCHAR(32) NOT NULL,
    reason VARCHAR(256) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_agent_scene_edit_plan(plan_id),
    KEY idx_agent_scene_edit_due(status,updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
