-- Key row is the serialization lock, including lazy creation of configuration/periods.
-- No wallet balance dependency; historical tasks are intentionally not backfilled.
CREATE TABLE api_key_budget_config (
    api_key_id BIGINT NOT NULL PRIMARY KEY,
    limit_amount DECIMAL(12,2) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_akbc_limit CHECK (limit_amount IS NULL OR limit_amount >= 0),
    CONSTRAINT ck_akbc_version CHECK (version >= 0)
) ENGINE=InnoDB;

CREATE TABLE api_key_budget_period (
    api_key_id BIGINT NOT NULL,
    period CHAR(7) NOT NULL,
    consumed DECIMAL(20,2) NOT NULL DEFAULT 0.00,
    reserved DECIMAL(20,2) NOT NULL DEFAULT 0.00,
    PRIMARY KEY (api_key_id, period),
    CONSTRAINT ck_akbp_consumed CHECK (consumed >= 0),
    CONSTRAINT ck_akbp_reserved CHECK (reserved >= 0)
) ENGINE=InnoDB;

CREATE TABLE api_key_budget_authorization (
    task_id BIGINT NOT NULL PRIMARY KEY,
    api_key_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    period CHAR(7) NOT NULL,
    amount DECIMAL(12,2) NOT NULL,
    state VARCHAR(16) NOT NULL,
    insertion_token VARCHAR(36) NULL COMMENT 'Immutable first-insert marker; never inferred from affected rows',
    CONSTRAINT ck_akba_amount CHECK (amount >= 0),
    CONSTRAINT ck_akba_state CHECK (state IN ('RESERVED', 'SETTLED', 'RELEASED')),
    KEY idx_akba_state_task (state, task_id),
    KEY idx_akba_key_period (api_key_id, period)
) ENGINE=InnoDB;

CREATE TABLE api_key_budget_ledger (
    task_id BIGINT NOT NULL,
    phase VARCHAR(16) NOT NULL,
    state VARCHAR(16) NOT NULL,
    api_key_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    period CHAR(7) NOT NULL,
    amount DECIMAL(12,2) NOT NULL,
    PRIMARY KEY (task_id, phase),
    CONSTRAINT ck_akbl_amount CHECK (amount >= 0),
    CONSTRAINT ck_akbl_phase CHECK (
        (phase = 'RESERVE' AND state = 'RESERVED') OR
        (phase = 'FINISH' AND state IN ('SETTLED', 'RELEASED'))),
    KEY idx_akbl_key_period (api_key_id, period)
) ENGINE=InnoDB;
