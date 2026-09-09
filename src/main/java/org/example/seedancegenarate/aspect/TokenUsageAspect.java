package org.example.seedancegenarate.aspect;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.example.seedancegenarate.context.UserContext;
import org.example.seedancegenarate.entity.AppUser;
import org.example.seedancegenarate.entity.PromptTokenUsage;
import org.example.seedancegenarate.service.TokenUsageService;
import org.example.seedancegenarate.service.llm.LlmCallMeta;
import org.example.seedancegenarate.service.llm.LlmChannelSpec;
import org.example.seedancegenarate.service.llm.LlmChannelException;
import org.example.seedancegenarate.service.llm.LlmChatResponse;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * LLM 调用 token 消耗统计切面：覆盖旧文本出口和受控LangChain4j Planner出口。
 * 两条调用链均显式传channel/messages/meta，Planner额外包含Schema字符估算。
 * <p>
 * 不变量：切面内任何异常只打日志，绝不影响主流程；token 数优先取响应 usage，
 * 缺失时按字符数/4 估算兜底。
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class TokenUsageAspect {

    /** 无 usage 时的粗略估算，标记ESTIMATED，不可当作中文模型的精确Token预算。 */
    private static final int TOKENS_PER_CHAR = 4;

    private final TokenUsageService tokenUsageService;

    @Around("execution(* org.example.seedancegenarate.service.llm.LlmChatClient.chat(..)) || execution(* org.example.seedancegenarate.service.llm.LangChain4jPlannerClient.chat(..))")
    public Object record(ProceedingJoinPoint pjp) throws Throwable {
        Object[] args = pjp.getArgs();
        // 第一个参数是通道：按通道记，两条通道可能用同名模型，只记模型名分不出是哪家
        LlmChannelSpec channel = (LlmChannelSpec) args[0];
        String llmModel = channel.model();
        String llmChannel = channel.name();
        LlmCallMeta meta = (LlmCallMeta) args[2];

        int promptLen = charsOf(args[1]);
        if(args.length>3 && args[3] instanceof com.fasterxml.jackson.databind.JsonNode schema)
            promptLen+=schema.toString().length();
        long start = System.currentTimeMillis();
        try {
            LlmChatResponse response = (LlmChatResponse) pjp.proceed();
            int responseLen = response.content().length();
            save(llmModel, llmChannel, meta, promptLen, responseLen,
                    estimateTokens(response.promptTokens(), promptLen),
                    estimateTokens(response.completionTokens(), responseLen),
                    System.currentTimeMillis() - start, "SUCCESS", null,
                    response.promptTokens() != null && response.completionTokens() != null ? "PROVIDER" : "ESTIMATED");
            return response;
        } catch (Throwable t) {
            LlmChannelException modelError = t instanceof LlmChannelException e ? e : null;
            Integer input = modelError == null ? null : modelError.promptTokens();
            Integer output = modelError == null ? null : modelError.completionTokens();
            // 只记录结构化分类，不把可能带请求正文/URL/密钥的异常消息写进管理端。
            String diagnostic = modelError == null ? "MODEL_CLIENT_ERROR" : modelError.code()
                    + (modelError.httpStatus() == null ? "" : " HTTP=" + modelError.httpStatus());
            save(llmModel, llmChannel, meta, promptLen, 0, input, output,
                    System.currentTimeMillis() - start, "FAILED", diagnostic,
                    input != null || output != null ? "PROVIDER" : "UNKNOWN");
            throw t;
        }
    }

    /** 记录落库：内部兜底，写库失败不影响 LLM 主流程 */
    private void save(String llmModel, String llmChannel, LlmCallMeta meta, int promptLen, int responseLen,
                      Integer promptTokens, Integer completionTokens, long latencyMs, String status, String errorMsg,
                      String usageSource) {
        try {
            PromptTokenUsage usage = new PromptTokenUsage();
            if (meta != null && meta.userId() != null) {
                usage.setUserId(meta.userId());
            } else {
                AppUser user = UserContext.getUser();
                if (user != null) {
                    usage.setUserId(user.getId());
                    usage.setUserName(user.getUsername());
                }
            }
            usage.setAgentTurnId(meta == null ? null : meta.agentTurnId());
            usage.setDecisionStep(meta == null ? null : meta.decisionStep());
            usage.setUsageSource(usageSource);
            usage.setScene(meta == null ? null : meta.scene());
            usage.setTargetModel(meta == null ? null : meta.targetModel());
            usage.setLlmModel(llmModel);
            usage.setLlmChannel(llmChannel);
            usage.setPromptTokens(promptTokens);
            usage.setCompletionTokens(completionTokens);
            usage.setTotalTokens(promptTokens == null || completionTokens == null ? null : promptTokens + completionTokens);
            usage.setPromptLen(promptLen);
            usage.setResponseLen(responseLen);
            usage.setLatencyMs(latencyMs);
            usage.setStatus(status);
            usage.setErrorMsg(errorMsg);
            tokenUsageService.record(usage);
        } catch (Exception e) {
            log.warn("token 消耗记录失败, scene:{}", meta == null ? null : meta.scene(), e);
        }
    }

    /** messages 全部正文的字符总数（估算输入长度的兜底依据） */
    private int charsOf(Object messagesObj) {
        return org.example.seedancegenarate.service.llm.LlmChatClient.inputTextChars(messagesObj);
    }

    /** usage 缺失时按字符数估算 */
    private Integer estimateTokens(Integer tokens, int chars) {
        return tokens != null ? tokens : Math.max(1, chars / TOKENS_PER_CHAR);
    }
}
