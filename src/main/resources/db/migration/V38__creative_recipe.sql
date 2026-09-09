CREATE TABLE creative_recipe (
    id VARCHAR(64) PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    name VARCHAR(30) NOT NULL,
    description VARCHAR(500) NOT NULL,
    instruction MEDIUMTEXT NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    revision BIGINT NOT NULL DEFAULT 1,
    latest_version INT NOT NULL DEFAULT 0,
    latest_version_id VARCHAR(64) NULL,
    compilation_json JSON NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_recipe_owner(owner_id,updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE creative_recipe_version (
    id VARCHAR(64) PRIMARY KEY,
    recipe_id VARCHAR(64) NOT NULL,
    version INT NOT NULL,
    name VARCHAR(30) NOT NULL,
    description VARCHAR(500) NOT NULL,
    instruction MEDIUMTEXT NOT NULL,
    definition_json JSON NOT NULL,
    content_hash VARCHAR(64) NOT NULL,
    compiler_version VARCHAR(32) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_recipe_version(recipe_id,version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE creative_recipe_command (
    owner_id BIGINT NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    recipe_id VARCHAR(64) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    response_json JSON NULL,
    error_code INT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(owner_id,request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
