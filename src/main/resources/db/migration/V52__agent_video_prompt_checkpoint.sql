-- Validated text only. These rows never authorize or submit media generation.
CREATE TABLE agent_video_prompt_checkpoint (
    call_id VARCHAR(64) NOT NULL,
    scene_key VARCHAR(64) NOT NULL,
    session_id VARCHAR(64) NOT NULL,
    turn_id VARCHAR(64) NOT NULL,
    execution_epoch BIGINT NOT NULL,
    plan_step_id VARCHAR(64) NULL,
    binding_hash CHAR(64) NOT NULL,
    ordinal INT NOT NULL,
    prompt TEXT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (call_id,scene_key),
    KEY idx_agent_video_prompt_reuse (session_id,plan_step_id,execution_epoch,binding_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
