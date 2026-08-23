-- 充值订单超时自动关闭：close_time 记录关闭时间（审计），索引供订单页按用户分页查询。
ALTER TABLE recharge_order
    ADD COLUMN close_time DATETIME NULL COMMENT '关闭时间（超时未支付自动关闭，PENDING→CLOSED）' AFTER paid_at,
    ADD INDEX idx_recharge_order_user_time (user_id, create_time);
