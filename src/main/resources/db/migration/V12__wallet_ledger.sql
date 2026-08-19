-- 账务系统第一批：钱包 + 余额流水账本 + 资金流入订单。
-- 铁律：余额真相在流水账本（biz_key 唯一约束幂等），wallet 只是缓存 + 行锁位；
--       一切金额变动 = 「INSERT 流水（撞唯一键=已处理）→ UPDATE wallet」同一事务。

-- 钱包（每用户一行，懒建）
CREATE TABLE IF NOT EXISTS wallet (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL COMMENT '用户',
    balance DECIMAL(12, 2) NOT NULL DEFAULT 0.00 COMMENT '可用余额（元）',
    frozen DECIMAL(12, 2) NOT NULL DEFAULT 0.00 COMMENT '冻结中（预授权未结算）',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁（预留）',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_wallet_user (user_id)
) COMMENT '用户钱包';

-- 余额流水账本（唯一真相；一切金额变动必写一行）
CREATE TABLE IF NOT EXISTS balance_transaction (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    type VARCHAR(32) NOT NULL COMMENT 'RECHARGE 渠道充值 / ADMIN_CREDIT 管理员加钱 / REWARD 奖励 / FREEZE 冻结 / SETTLE 结算 / RELEASE 解冻 / REFUND 退款 / ADJUST 人工调整',
    amount DECIMAL(12, 2) NOT NULL COMMENT '正=流入 负=流出',
    balance_after DECIMAL(12, 2) NOT NULL DEFAULT 0.00 COMMENT '变更后可用余额（对账用）',
    biz_key VARCHAR(128) NOT NULL COMMENT '幂等键：充值=order_no、任务冻结=task:{id}、结算=task:{id}:settle、解冻=task:{id}:release',
    task_id BIGINT NULL COMMENT '关联生成任务（FREEZE/SETTLE/RELEASE）',
    ref_order_no VARCHAR(64) NULL COMMENT '关联资金单号（RECHARGE/ADMIN_CREDIT → recharge_order.order_no）',
    operator_id BIGINT NULL COMMENT '操作管理员（ADMIN_CREDIT/ADJUST 必填）',
    operator_name VARCHAR(64) NULL COMMENT '操作管理员名',
    coupon_id BIGINT NULL COMMENT '关联优惠券（预留第二批）',
    remark VARCHAR(255) NULL COMMENT '原因/说明',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_bt_biz_key (biz_key),
    KEY idx_bt_user_time (user_id, create_time)
) COMMENT '余额流水账本';

-- 资金流入订单（充值/管理员加钱统一走这张表，channel 区分来源）
CREATE TABLE IF NOT EXISTS recharge_order (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_no VARCHAR(64) NOT NULL COMMENT '我方单号（幂等键）',
    user_id BIGINT NOT NULL,
    channel VARCHAR(32) NOT NULL COMMENT 'admin 管理员加钱 / wechat / alipay（第二批）',
    channel_txn_id VARCHAR(128) NULL COMMENT '渠道单号（admin 渠道 = order_no）',
    amount DECIMAL(12, 2) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'SUCCESS' COMMENT 'PENDING / SUCCESS / FAILED / REFUNDED',
    operator_id BIGINT NULL COMMENT '管理员加钱操作人',
    operator_name VARCHAR(64) NULL,
    reason VARCHAR(255) NULL COMMENT '加钱原因',
    paid_at DATETIME NULL,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_ro_order_no (order_no),
    UNIQUE KEY uk_ro_channel_txn (channel, channel_txn_id)
) COMMENT '资金流入订单';

-- 存量用户钱包初始化（幂等：重复执行撞 uk_wallet_user 自动跳过）
INSERT INTO wallet (user_id, balance, frozen)
SELECT id, 0.00, 0.00 FROM app_user;
