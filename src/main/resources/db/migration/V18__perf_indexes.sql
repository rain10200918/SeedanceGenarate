-- 性能与索引优化：
-- 1. balance_transaction 补齐 task_id 索引，解决 TaskReconcileTask 每 30 秒全表扫的严重性能隐患
-- 2. async_job 补齐 (job_type, status, available_at, id) 复合索引，优化 Worker claim 效率

CREATE INDEX idx_bt_task_type ON balance_transaction (task_id, type);

CREATE INDEX idx_job_claim_v2 ON async_job (job_type, status, available_at, id);
