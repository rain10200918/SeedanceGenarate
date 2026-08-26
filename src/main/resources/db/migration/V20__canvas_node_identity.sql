-- 画布 P0 地基：节点稳定身份 + 坐标 + 连线表 + 增量保存的并发控制列
--
-- 版本号说明：V19 预留给进行中的 wallet_state 变更（见 .my-loop/CURRENT.md），本迁移取 V20 避让。
--
-- 为什么需要 node_key：原 saveNodes 是「删旧插新」，每次保存节点主键全变。画布是拖一下就自动保存的
-- 场景，主键漂移会让连线（按 key 引用）断裂、让运行中节点的 jobKey(pipelineId, nodeId) 幂等失效、
-- 让已回填的 task_id/结果丢失。稳定身份是画布的地基，必须先于一切画布功能落地。

ALTER TABLE pipeline
    ADD COLUMN kind VARCHAR(16) NOT NULL DEFAULT 'LINEAR' COMMENT 'LINEAR 线性流水 / CANVAS 画布',
    ADD COLUMN viewport JSON NULL COMMENT '画布视口 {x,y,zoom}',
    ADD COLUMN canvas_version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观并发版本号，每次增量保存 +1（不用 update_time：DATETIME 秒精度会漏判同秒冲突）',
    ADD COLUMN last_mutation_id VARCHAR(64) NULL COMMENT '最后应用的保存幂等键；重复提交据此识别重放并返回原结果';

ALTER TABLE pipeline_node
    ADD COLUMN node_key VARCHAR(64) NULL COMMENT '稳定身份（客户端生成 UUID），跨保存不变',
    ADD COLUMN pos_x INT NOT NULL DEFAULT 0 COMMENT '画布坐标 X',
    ADD COLUMN pos_y INT NOT NULL DEFAULT 0 COMMENT '画布坐标 Y',
    ADD COLUMN width INT NULL COMMENT '画布节点宽；空=前端默认',
    ADD COLUMN height INT NULL COMMENT '画布节点高；空=前端默认',
    ADD COLUMN metadata JSON NULL COMMENT '可变业务字段（骨架列保持极简，新增能力放这里，不再改表）';

-- 存量回填：UUID() 在 UPDATE 中逐行求值，天然互不相同
UPDATE pipeline_node SET node_key = REPLACE(UUID(), '-', '') WHERE node_key IS NULL;

ALTER TABLE pipeline_node
    MODIFY COLUMN node_key VARCHAR(64) NOT NULL COMMENT '稳定身份（客户端生成 UUID），跨保存不变',
    ADD UNIQUE KEY uk_node_pipeline_key (pipeline_id, node_key);

-- 连线独立成表（而非挂在节点 JSON 里）：增删连线不触碰节点行，
-- 避免与执行器回填节点状态相互覆盖。
CREATE TABLE pipeline_edge (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    pipeline_id BIGINT NOT NULL COMMENT '所属流水线/画布',
    edge_key VARCHAR(64) NOT NULL COMMENT '稳定身份（客户端生成 UUID）',
    from_node_key VARCHAR(64) NOT NULL COMMENT '上游节点 node_key',
    to_node_key VARCHAR(64) NOT NULL COMMENT '下游节点 node_key',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_edge_pipeline_key (pipeline_id, edge_key),
    UNIQUE KEY uk_edge_pipeline_pair (pipeline_id, from_node_key, to_node_key) COMMENT '同一对节点不重复连线',
    KEY idx_edge_pipeline_to (pipeline_id, to_node_key) COMMENT '就绪判定：查某节点的全部上游'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='画布连线（DAG 边）';
