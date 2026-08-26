-- 无限画布独立数据模型。
--
-- 为什么不继续挂在 pipeline / pipeline_node 上：那套结构是「一个节点 = 一段视频镜头」，
-- 装不下素材节点、文本节点，也表达不了「连到哪个输入端口」。画布是独立功能，给它独立的表。
-- V20 加在 pipeline 上的画布字段与 pipeline_edge 表由后续迁移清理（等前端切换完成）。
--
-- 关键设计：
-- 1. node_key / edge_key 是客户端生成的稳定身份，跨保存不变（增量保存按 key upsert，绝不删旧插新）
-- 2. 节点参数进 config JSON —— 新增节点类型不用改表，由该类型的 CanvasNodeType 实现自己解释
-- 3. 边带 from_port / to_port —— 连到哪个输入口是画布的核心信息

CREATE TABLE canvas (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL COMMENT '属主用户',
    title VARCHAR(128) NOT NULL DEFAULT '未命名画布',
    viewport JSON NULL COMMENT '视口 {x,y,zoom}，下次打开回到原位',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观并发版本号，每次增量保存 +1（不用 update_time：秒精度会漏判同秒冲突）',
    last_mutation_id VARCHAR(64) NULL COMMENT '最后应用的保存幂等键；重复提交据此识别重放',
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/RUNNING/DONE/PARTIAL_FAILED',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_canvas_user (user_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='无限画布';

CREATE TABLE canvas_node (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    canvas_id BIGINT NOT NULL,
    node_key VARCHAR(64) NOT NULL COMMENT '稳定身份（客户端 UUID），跨保存不变',
    node_type VARCHAR(24) NOT NULL COMMENT '注册表 key：ASSET 素材 / TEXT 文本 / GENERATE 生成',
    title VARCHAR(128) NULL,
    pos_x INT NOT NULL DEFAULT 0,
    pos_y INT NOT NULL DEFAULT 0,
    width INT NULL,
    height INT NULL,
    config JSON NULL COMMENT '该类型自己的参数（模型/提示词/素材引用/文本内容…），由 CanvasNodeType 解释',
    status VARCHAR(16) NOT NULL DEFAULT 'IDLE' COMMENT 'IDLE 无需执行 / PENDING 待运行 / PROCESSING / SUCCESS / FAILED / BLOCKED 上游未就绪',
    task_id VARCHAR(128) NULL COMMENT '生成节点关联的 video_task.biz_task_id（终态事件反查键）',
    submit_request_id VARCHAR(128) NULL COMMENT '本次提交幂等键，重试/重启不重复建任务',
    output JSON NULL COMMENT '产物 {mediaType,url}；形状对所有类型统一，下游按端口取用',
    error_msg VARCHAR(512) NULL,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_cnode_canvas_key (canvas_id, node_key),
    KEY idx_cnode_task (task_id) COMMENT '终态事件按 taskId 反查节点',
    KEY idx_cnode_canvas_status (canvas_id, status) COMMENT '就绪判定/对账扫描'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='画布节点';

CREATE TABLE canvas_edge (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    canvas_id BIGINT NOT NULL,
    edge_key VARCHAR(64) NOT NULL COMMENT '稳定身份（客户端 UUID）',
    from_node_key VARCHAR(64) NOT NULL COMMENT '上游节点',
    from_port VARCHAR(32) NOT NULL DEFAULT 'out' COMMENT '上游输出端口（当前每个节点单输出，预留多输出）',
    to_node_key VARCHAR(64) NOT NULL COMMENT '下游节点',
    to_port VARCHAR(32) NOT NULL COMMENT '下游输入端口：prompt / image / video / audio',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_cedge_canvas_key (canvas_id, edge_key),
    UNIQUE KEY uk_cedge_link (canvas_id, from_node_key, to_node_key, to_port) COMMENT '同一上游不重复接入同一端口',
    KEY idx_cedge_to (canvas_id, to_node_key) COMMENT '就绪判定：查某节点的全部上游'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='画布连线（带端口的 DAG 边）';
