-- Harden billing idempotency for distributed terminal processing.
-- This migration intentionally does not delete historical duplicate billing records.
-- If duplicate cost_record.task_id rows already exist, Flyway will fail while adding
-- the unique key; resolve them by manual reconciliation before rerunning migration.

ALTER TABLE cost_record
  DROP INDEX idx_cost_record_task_id,
  ADD UNIQUE KEY uk_cost_record_task_id (task_id);
