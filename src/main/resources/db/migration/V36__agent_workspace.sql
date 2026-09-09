-- 工作区身份及创作谱系只存MySQL；旧会话按空工作区恢复。
ALTER TABLE agent_session ADD COLUMN workspace_json JSON NULL;
ALTER TABLE agent_skill_call ADD COLUMN context_json JSON NULL;
ALTER TABLE agent_artifact_version ADD COLUMN data_json JSON NULL;
ALTER TABLE agent_artifact_version ADD COLUMN source_ref_json JSON NULL;
ALTER TABLE agent_artifact_version ADD COLUMN plan_ref_json JSON NULL;
ALTER TABLE agent_artifact_version ADD COLUMN step_id VARCHAR(64) NULL;
