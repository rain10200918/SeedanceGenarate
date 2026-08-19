-- 冻结金额快照：提交时把预授权金额记在任务上。
-- 为什么必须快照：结算/解冻若用「实时价」，管理员改价后结算金额 ≠ 冻结金额，
-- frozen 余额会漂移。价格是可变配置，冻结金额是历史事实，必须留在任务记录上。

ALTER TABLE video_task ADD COLUMN freeze_amount DECIMAL(12, 2) NULL COMMENT '提交时冻结金额（预授权快照）';
