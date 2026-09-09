-- Bind a durable Turn to its model/provider identity, not to secrets or sampling options.
ALTER TABLE agent_turn ADD COLUMN model_binding VARCHAR(64) NULL;
