-- 账本金额口径补强：amount 表示账户净资产变动，hold_amount 表示冻结/结算/解冻的预授权金额。
-- FREEZE 是 balance → frozen 的内部转移，amount 必须为 0；SETTLE 才减少净资产。
ALTER TABLE balance_transaction
    ADD COLUMN hold_amount DECIMAL(12, 2) NULL COMMENT '预授权金额；冻结/结算/解冻使用，amount 仍按净资产口径' AFTER amount;

-- 历史冻结流水只有一条，按关联任务快照补齐预授权金额，并把冻结的净资产变动归零。
UPDATE balance_transaction b
JOIN video_task v ON v.id = b.task_id
SET b.hold_amount = COALESCE(v.freeze_amount, ABS(b.amount)),
    b.amount = 0
WHERE b.type = 'FREEZE'
  AND b.hold_amount IS NULL;

UPDATE balance_transaction
SET hold_amount = ABS(amount)
WHERE type IN ('SETTLE', 'RELEASE')
  AND hold_amount IS NULL;
