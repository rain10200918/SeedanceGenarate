-- Do not infer historical fingerprints from transformed OSS reference URLs.
-- External Idempotency-Key remains bounded by the existing request_id VARCHAR(64).
ALTER TABLE api_call_log ADD COLUMN request_fingerprint VARCHAR(64) NULL;
