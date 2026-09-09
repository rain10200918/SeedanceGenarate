-- A repair only regenerates unapproved text; it never authorizes a media task.
ALTER TABLE agent_video_prompt_checkpoint ADD COLUMN repair_count INT NOT NULL DEFAULT 0;
ALTER TABLE agent_video_prompt_checkpoint ADD COLUMN validation_code VARCHAR(64) NULL;
ALTER TABLE agent_video_prompt_checkpoint ADD COLUMN validation_detail VARCHAR(512) NULL;
ALTER TABLE agent_video_prompt_checkpoint ADD COLUMN diagnostic_id VARCHAR(64) NULL;
