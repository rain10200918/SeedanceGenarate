-- 修复：task 744 无背书解冻挪走 1.80，导致 task 764 退不出 2.40
-- 背景与判定过程见 .my-loop/CURRENT-wallet-hold-leak.md
--
-- 前提：代码修复（WalletServiceImpl 前置守卫）必须先上线，否则修好还会再被挖一次。
-- 执行者：人。生产库，逐段跑，每段都看输出。

-- ============================================================
-- ① 执行前快照（把输出留档，出问题好回退）
-- ============================================================
SELECT '执行前' AS phase, balance, frozen, balance + frozen AS total
FROM wallet WHERE user_id = 22;
-- 预期: balance=981.01  frozen=0.60  total=981.61

SELECT id, type, amount, hold_amount, balance_after, frozen_after, biz_key, task_id, create_time
FROM balance_transaction WHERE user_id = 22 ORDER BY id;

-- ② 再确认一次判定（可选但建议）：id=36 前一行的 balance_after 应为 988.50
--    若是 990.30，说明 744 其实没动钱包，本脚本不适用，停下来重新判定。
SELECT id, type, hold_amount, balance_after, frozen_after, biz_key
FROM balance_transaction WHERE user_id = 22 AND id < 36 ORDER BY id DESC LIMIT 1;

-- ============================================================
-- ③ 修复（一个事务；两条 UPDATE 的行数都必须是 1）
-- ============================================================
START TRANSACTION;

-- ③-1 把 744 无背书提走的 1.80 还回 frozen 池
--     条件卡死当前值，避免在别人并发改动后误更新
UPDATE wallet
SET frozen  = frozen  + 1.80,
    balance = balance - 1.80
WHERE user_id = 22 AND balance = 981.01 AND frozen = 0.60;
-- 必须 Rows matched: 1

-- ③-2 把那条流水的 hold 改成 0：它宣称退了 1.80 的冻结额，而这笔冻结额从来不属于 744
--     （744 没有 FREEZE 流水）。改完 frozen 维度对账才会平。
UPDATE balance_transaction
SET hold_amount = 0.00,
    remark      = CONCAT(COALESCE(remark, ''), '[2026-08-26 修正：该任务从未冻结，原 hold 1.80 系挪用他人冻结额]')
WHERE id = 36 AND biz_key = 'task:744:release' AND hold_amount = 1.80;
-- 必须 Rows matched: 1

COMMIT;

-- ============================================================
-- ④ 验证
-- ============================================================
SELECT '修复后' AS phase, balance, frozen, balance + frozen AS total
FROM wallet WHERE user_id = 22;
-- 预期: balance=979.21  frozen=2.40  total=981.61   ← total 不变，钱没有凭空增减

-- 冻结维度对账应当为空（这就是新加的 findFrozenMismatches 的口径）
SELECT w.user_id,
       COALESCE(SUM(CASE WHEN b.type='FREEZE' THEN b.hold_amount
                         WHEN b.type IN ('SETTLE','RELEASE') THEN -b.hold_amount
                         ELSE 0 END),0) AS ledger_hold_net,
       w.frozen
FROM wallet w LEFT JOIN balance_transaction b ON b.user_id = w.user_id
WHERE w.user_id = 22
GROUP BY w.user_id, w.frozen
HAVING ABS(ledger_hold_net - w.frozen) > 0.005;
-- 预期: 空

-- ============================================================
-- ⑤ 剩下的交给程序
-- ============================================================
-- 不用手工给 764 补 RELEASE 流水。frozen 恢复到 2.40 后，
-- TaskReconcileTask 下一轮（≤30s）会自己调 release(22, 2.40, 764) 并成功，
-- 那条每 30 秒刷屏的 WARN 随之消失。
--
-- 最终态: balance=981.61  frozen=0.00
SELECT '30 秒后复查' AS phase, balance, frozen FROM wallet WHERE user_id = 22;
SELECT id, type, hold_amount, biz_key FROM balance_transaction WHERE task_id = 764 ORDER BY id;
-- 预期出现: FREEZE hold=2.40 / RELEASE hold=2.40
