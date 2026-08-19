-- 账务一致性补强：冻结时同时快照单价/币种；所有生成请求保留幂等键。
-- freeze_amount / freeze_unit_price / freeze_currency 是用户账务历史事实，
-- 成功结算和消费记录不得重新读取管理员当前价格。
ALTER TABLE video_task
    ADD COLUMN freeze_unit_price DECIMAL(12, 4) NULL COMMENT '提交时单价快照' AFTER freeze_amount,
    ADD COLUMN freeze_currency VARCHAR(16) NULL COMMENT '提交时币种快照' AFTER freeze_unit_price,
    ADD COLUMN request_id VARCHAR(128) NULL COMMENT '用户提交幂等键；同一用户唯一' AFTER api_key_id,
    ADD UNIQUE KEY uk_video_task_user_request (user_id, request_id);

ALTER TABLE recharge_order
    ADD COLUMN request_id VARCHAR(128) NULL COMMENT '管理员加钱请求幂等键' AFTER channel_txn_id,
    ADD UNIQUE KEY uk_recharge_user_request (user_id, request_id);

ALTER TABLE pipeline_node
    ADD COLUMN submit_request_id VARCHAR(128) NULL COMMENT '本次节点提交幂等键' AFTER task_id;
