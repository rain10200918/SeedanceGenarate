-- 对话式创作：一条对话里按顺序排着「用户原话 / Agent 整理的提示词 / 生成卡片」。
-- 生成卡片只是 video_task 的投影：提交、执行、计费、进度推送全部走既有链路（和 canvas_node 同一套路）。

CREATE TABLE conversation (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id         BIGINT       NOT NULL COMMENT '属主用户',
    title           VARCHAR(128) NOT NULL DEFAULT '新对话',
    title_source    VARCHAR(16)  NOT NULL DEFAULT 'AUTO' COMMENT 'AUTO 由首条消息生成 / USER 用户改过名，之后不再自动覆盖',
    last_message_at DATETIME     NULL COMMENT '左栏排序键',
    message_count   INT          NOT NULL DEFAULT 0 COMMENT '已分配的 seq 上限：发一轮消息时在行锁内一次预留整轮的槽位',
    archived        TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '只归档不删',
    create_time     DATETIME     DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_conv_user (user_id, archived, last_message_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '对话式创作：对话';

CREATE TABLE conversation_message (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    conversation_id BIGINT       NOT NULL,
    user_id         BIGINT       NOT NULL COMMENT '冗余属主：越权判定只查这一张表',
    seq             INT          NOT NULL COMMENT '会话内顺序号，翻页用它不用 id',
    role            VARCHAR(16)  NOT NULL COMMENT 'USER / ASSISTANT',
    kind            VARCHAR(16)  NOT NULL COMMENT 'TEXT / GENERATION',
    content         TEXT         NULL COMMENT '文本气泡：用户原话 / Agent 整理后的提示词 / Agent 不可用时的说明',
    attachments     JSON         NULL COMMENT '用户带的参考素材 [{type: image|video|audio, url}]',
    task_id         VARCHAR(128) NULL COMMENT 'GENERATION：video_task.biz_task_id，终态事件反查键',
    gen_params      JSON         NULL COMMENT 'GENERATION 提交快照 {provider, model, prompt, ratio, duration, megapixels, imageUrls, videoUrls, audioUrls}；「再生成一次」靠它',
    gen_status      VARCHAR(16)  NULL COMMENT 'GENERATION：PENDING / PROCESSING / SUCCESS / FAILED，video_task 状态的镜像，事件回填',
    output          JSON         NULL COMMENT 'GENERATION 产物 {mediaType, url, cost}，与 canvas_node.output 同形状',
    error_msg       VARCHAR(512) NULL,
    client_msg_id   VARCHAR(64)  NULL COMMENT '客户端幂等键：网络重试不会发出第二轮',
    create_time     DATETIME     DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_conv_msg_seq (conversation_id, seq),
    UNIQUE KEY uk_conv_msg_client (conversation_id, client_msg_id),
    KEY idx_conv_msg_task (task_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '对话式创作：消息';
