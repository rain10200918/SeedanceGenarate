ALTER TABLE agent_plan_step ADD COLUMN scope VARCHAR(32) NOT NULL DEFAULT 'SINGLE';
ALTER TABLE agent_plan_step ADD COLUMN source_step_key VARCHAR(64) NULL;
ALTER TABLE agent_plan_step ADD COLUMN source_ref JSON NULL;
CREATE TABLE agent_plan_scene (
    id VARCHAR(64) PRIMARY KEY,
    plan_step_id VARCHAR(64) NOT NULL,
    scene_key VARCHAR(64) NOT NULL,
    ordinal_no INT NOT NULL,
    status VARCHAR(32) NOT NULL,
    source_ref JSON NOT NULL,
    skill_call_id VARCHAR(64) NULL,
    result_ref JSON NULL,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_agent_scene_key(plan_step_id,scene_key),
    UNIQUE KEY uk_agent_scene_order(plan_step_id,ordinal_no),
    UNIQUE KEY uk_agent_scene_call(skill_call_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
