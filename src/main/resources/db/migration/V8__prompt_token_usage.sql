-- 提示词优化 token 消耗明细：TokenUsageAspect 切 LlmChatClient.chat() 自动记录，成功与失败都记。
-- scene 区分用途（PROMPT_OPTIMIZE=提示词优化），未来其他 LLM 场景（翻译/打标）沿用同一张表，切面复用。
-- token 数优先取响应 usage 字段；代理不返回 usage 时按字符数/4 估算兜底。

CREATE TABLE prompt_token_usage (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    user_id BIGINT NULL COMMENT '调用用户 ID（UserContext）',
    user_name VARCHAR(64) NULL COMMENT '调用用户名（冗余，管理端免 join）',
    scene VARCHAR(32) NOT NULL COMMENT '用途：PROMPT_OPTIMIZE=提示词优化',
    llm_model VARCHAR(64) NOT NULL COMMENT '实际调用的 LLM 模型',
    target_model VARCHAR(64) NULL COMMENT '业务目标模型（如被优化的视频模型）',
    prompt_tokens INT NOT NULL DEFAULT 0 COMMENT '输入 token（usage 缺失时按字符估算）',
    completion_tokens INT NOT NULL DEFAULT 0 COMMENT '输出 token',
    total_tokens INT NOT NULL DEFAULT 0 COMMENT '合计（输入+输出）',
    prompt_len INT NULL COMMENT '原始提示词字符数',
    response_len INT NULL COMMENT '返回内容字符数',
    latency_ms BIGINT NULL COMMENT 'LLM 调用耗时（毫秒）',
    status VARCHAR(16) NOT NULL DEFAULT 'SUCCESS' COMMENT 'SUCCESS / FAILED',
    error_msg VARCHAR(255) NULL COMMENT '失败原因（不含密钥）',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录时间',
    KEY idx_ptu_user_time (user_id, create_time),
    KEY idx_ptu_time (create_time),
    KEY idx_ptu_scene (scene, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='提示词优化 token 消耗记录';
