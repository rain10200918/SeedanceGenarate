CREATE TABLE agent_recipe_run (
    id VARCHAR(64) PRIMARY KEY,
    session_id VARCHAR(64) NOT NULL,
    recipe_version_id VARCHAR(64) NOT NULL,
    turn_id VARCHAR(64) NULL,
    plan_id VARCHAR(64) NULL,
    goal TEXT NULL,
    execution_epoch BIGINT NOT NULL DEFAULT 1,
    version BIGINT NOT NULL DEFAULT 1,
    stage_index INT NOT NULL DEFAULT 0,
    status VARCHAR(32) NOT NULL DEFAULT 'RUNNING',
    variables_json JSON NOT NULL,
    results_json JSON NOT NULL,
    approvals_json JSON NOT NULL,
    plan_steps_json JSON NULL,
    decision_count INT NOT NULL DEFAULT 0,
    interaction_count INT NOT NULL DEFAULT 0,
    last_decision_key VARCHAR(160) NULL,
    reason VARCHAR(512) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_recipe_run_session (session_id,created_at),
    KEY idx_recipe_run_turn (turn_id),
    KEY idx_recipe_run_version (recipe_version_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE agent_session ADD COLUMN active_recipe_run_id VARCHAR(64) NULL;
