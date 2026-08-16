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
import org.example.seedancegenarate.service.llm.LlmChatResponse;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * LLM 调用 token 消耗统计切面：切 LlmChatClient.chat() 这个唯一出口，
 * 未来新增 LLM 调用场景（走同一客户端）自动纳入统计，无需改这里。
 * <p>
 * 不变量：切面内任何异常只打日志，绝不影响主流程；token 数优先取响应 usage，
 * 缺失时按字符数/4 估算兜底。
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class TokenUsageAspect {

    /** 无 usage 时的估算系数：约 1 个 token ≈ 4 字符（中文略保守，兜底用足够） */
    private static final int TOKENS_PER_CHAR = 4;
    /** 失败原因截断长度（防脏数据） */
    private static final int ERROR_MSG_MAX = 200;

    private final TokenUsageService tokenUsageService;

    @Around("execution(* org.example.seedancegenarate.service.llm.LlmChatClient.chat(..))")
    public Object record(ProceedingJoinPoint pjp) throws Throwable {
        Object[] args = pjp.getArgs();
        String llmModel = (String) args[0];
        LlmCallMeta meta = (LlmCallMeta) args[2];

        int promptLen = charsOf(args[1]);
        long start = System.currentTimeMillis();
        try {
            LlmChatResponse response = (LlmChatResponse) pjp.proceed();
            int responseLen = response.content().length();
            save(llmModel, meta, promptLen, responseLen,
                    estimateTokens(response.promptTokens(), promptLen),
                    estimateTokens(response.completionTokens(), responseLen),
                    System.currentTimeMillis() - start, "SUCCESS", null);
            return response;
        } catch (Throwable t) {
            save(llmModel, meta, promptLen, 0, null, null,
                    System.currentTimeMillis() - start, "FAILED",
                    t.getMessage() == null ? null : t.getMessage().substring(0, Math.min(t.getMessage().length(), ERROR_MSG_MAX)));
            throw t;
        }
    }

    /** 记录落库：内部兜底，写库失败不影响 LLM 主流程 */
    private void save(String llmModel, LlmCallMeta meta, int promptLen, int responseLen,
                      Integer promptTokens, Integer completionTokens, long latencyMs, String status, String errorMsg) {
        try {
            PromptTokenUsage usage = new PromptTokenUsage();
            AppUser user = UserContext.getUser();
            if (user != null) {
                usage.setUserId(user.getId());
                usage.setUserName(user.getUsername());
            }
            usage.setScene(meta == null ? null : meta.scene());
            usage.setTargetModel(meta == null ? null : meta.targetModel());
            usage.setLlmModel(llmModel);
            usage.setPromptTokens(promptTokens);
            usage.setCompletionTokens(completionTokens);
            usage.setTotalTokens(promptTokens == null ? null : promptTokens + (completionTokens == null ? 0 : completionTokens));
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
        if (!(messagesObj instanceof List<?> list)) {
            return 0;
        }
        int chars = 0;
        for (Object item : list) {
            if (item instanceof Map<?, ?> m && m.get("content") instanceof String content) {
                chars += content.length();
            }
        }
        return chars;
    }

    /** usage 缺失时按字符数估算 */
    private Integer estimateTokens(Integer tokens, int chars) {
        return tokens != null ? tokens : Math.max(1, chars / TOKENS_PER_CHAR);
    }
}
