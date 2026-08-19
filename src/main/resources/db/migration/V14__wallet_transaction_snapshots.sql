-- 钱包流水补充冻结余额快照。
-- 新流水会同时记录 balance_after / frozen_after；V12/V13 之前的历史流水无法从旧字段
-- 无损还原每一时刻的冻结余额，因此保留 NULL，前端显示“历史记录”。
ALTER TABLE balance_transaction
    ADD COLUMN frozen_after DECIMAL(12, 2) NULL COMMENT '变更后冻结余额（历史流水可能为空）' AFTER balance_after;
