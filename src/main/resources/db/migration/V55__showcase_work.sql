CREATE TABLE showcase_work (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    client_request_id VARCHAR(36) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    title VARCHAR(128) NOT NULL,
    description VARCHAR(2000) NOT NULL DEFAULT '',
    media_type VARCHAR(5) NOT NULL,
    media_key VARCHAR(255) NOT NULL,
    cover_key VARCHAR(255),
    status VARCHAR(9) NOT NULL DEFAULT 'DRAFT',
    sort_order INT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    published_at TIMESTAMP(6) NULL,
    CONSTRAINT uk_showcase_request UNIQUE (owner_id, client_request_id),
    CONSTRAINT ck_showcase_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'OFFLINE')),
    CONSTRAINT ck_showcase_media CHECK (media_type IN ('VIDEO', 'IMAGE')),
    CONSTRAINT ck_showcase_sort CHECK (sort_order BETWEEN 0 AND 999999)
);
CREATE INDEX idx_showcase_listing ON showcase_work(status, sort_order, published_at, id);
