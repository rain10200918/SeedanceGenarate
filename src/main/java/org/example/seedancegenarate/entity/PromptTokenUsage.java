package org.example.seedancegenarate.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 提示词优化 token 消耗记录（TokenUsageAspect 切 LlmChatClient.chat() 写入）。
 * 成功与失败都记录；token 数优先取响应 usage，缺失时按字符数估算。
 */
@Data
@TableName("prompt_token_usage")
public class PromptTokenUsage {
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 调用用户 ID（UserContext） */
    private Long userId;
    /** 调用用户名（冗余，管理端免 join） */
    private String userName;
    /** 用途：PROMPT_OPTIMIZE=提示词优化 */
    private String scene;
    /** 实际调用的 LLM 模型 */
    private String llmModel;
    /** 业务目标模型（如被优化的视频模型） */
    private String targetModel;
    /** 输入 token（usage 缺失时按字符估算） */
    private Integer promptTokens;
    /** 输出 token */
    private Integer completionTokens;
    /** 合计 */
    private Integer totalTokens;
    /** 原始提示词字符数 */
    private Integer promptLen;
    /** 返回内容字符数 */
    private Integer responseLen;
    /** LLM 调用耗时（毫秒） */
    private Long latencyMs;
    /** SUCCESS / FAILED */
    private String status;
    /** 失败原因（不含密钥） */
    private String errorMsg;
    /** 记录时间（DB 默认值填充） */
    private LocalDateTime createTime;
}
