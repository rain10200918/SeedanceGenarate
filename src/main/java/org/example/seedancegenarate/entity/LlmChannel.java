package org.example.seedancegenarate.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 一条提示词优化用的 LLM 通道（OpenAI 兼容 chat completions），<b>只存人填的</b>。
 * <p>
 * 主键是业务名（{@code default} / {@code deepseek-v3}）不是自增：
 * {@code prompt_token_usage.llm_channel} 存的就是它，人念得出来，排障值钱。建后不可改名。
 * <p>
 * 这张表里有密钥。它只被 {@code LlmChatClient} 读去发请求；管理端返回一律脱敏，
 * 日志和异常消息里不许出现（D-023）。
 */
@Data
@TableName("llm_channel")
public class LlmChannel {
    @TableId(type = IdType.INPUT)
    private String name;
    private String baseUrl;
    private String apiKey;
    private String model;
    /** NULL = 请求体里不带 temperature（推理类模型会拒绝这个参数） */
    private BigDecimal temperature;
    private Integer maxTokens;
    /** max_tokens / max_completion_tokens / none —— 同是 OpenAI 兼容，这个字段名分了两派 */
    private String tokenParam;
    /** 读超时。必须小于前端 axios 的 120s，写入时校验 */
    private Integer timeoutMs;
    /** 小的先用 */
    private Integer priority;
    /** 参不参与路由。<b>新增默认 false</b> —— 先试跑看输出质量再开 */
    private Boolean enabled;
    /** 列表里显不显示。归档连带停用；行永远留着，usage 才能回查 */
    private Boolean archived;
    private String remark;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
