-- Turn execution slices are durable. Existing rows may keep a NULL sequence;
-- every new Turn is allocated a non-null per-session sequence while holding the session lock.
ALTER TABLE agent_turn ADD COLUMN trigger_type VARCHAR(32) NOT NULL DEFAULT 'USER_MESSAGE';
ALTER TABLE agent_turn ADD COLUMN parent_turn_id VARCHAR(64) NULL;
ALTER TABLE agent_turn ADD COLUMN turn_seq BIGINT NULL;
ALTER TABLE agent_turn ADD COLUMN yield_reason VARCHAR(32) NULL;
ALTER TABLE agent_turn ADD UNIQUE KEY uk_agent_turn_session_seq(session_id,turn_seq);
ALTER TABLE agent_turn ADD UNIQUE KEY uk_agent_turn_parent(parent_turn_id);
