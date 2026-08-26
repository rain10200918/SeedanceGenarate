package org.example.seedancegenarate.service.llm;

import cn.hutool.core.io.IORuntimeException;
import org.example.seedancegenarate.config.PromptOptimizeConfig;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.SocketTimeoutException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「AI 润色」长文本超时守卫。
 * 2026-08-26 实测：1521 字的分镜脚本，LLM 要 68.8s 才出完 1199 个 token（≈18 tok/s），
 * 而当时上限 60s —— 调用其实成功了，是我们自己先挂断，然后报 500。
 */
class LlmChatTimeoutTest {

    @Test
    void backendTimeoutStaysInsideFrontendBudget() {
        // 测什么：后端 LLM 超时上限必须小于前端 axios 的 120s
        // 怎么算红：谁把它调到 ≥120000 —— 前端会先断连，后端还在白烧 token，
        //          用户看到的症状和今天这个 bug 一模一样，但排查时会以为后端已经放宽了
        int frontendAxiosTimeoutMs = 120_000; // src/api/http.ts: timeout: 120_000
        int timeoutMs = new PromptOptimizeConfig().getTimeoutMs();

        assertTrue(timeoutMs < frontendAxiosTimeoutMs,
                "后端上限 " + timeoutMs + "ms 必须小于前端 " + frontendAxiosTimeoutMs + "ms，否则前端先断");
        assertTrue(timeoutMs >= 90_000,
                "maxTokens=1500 按实测 ≈18 tok/s 最坏要 ≈85s，低于 90s 会把长提示词一律判死，实际 " + timeoutMs);
    }

    @Test
    void hutoolWrappedSocketTimeoutIsRecognized() {
        // 测什么：Hutool 把 SocketTimeoutException 包在 IORuntimeException 里，要能顺着 cause 认出来
        // 怎么算红：只看最外层类型 —— 超时会被当成普通调用失败，报「请稍后再试」，
        //          用户不知道其实是自己的提示词太长、缩短一点就能过
        Exception wrapped = new IORuntimeException(new SocketTimeoutException("Read timed out"));

        assertTrue(LlmChatClient.isTimeout(wrapped));
    }

    @Test
    void nonTimeoutFailureIsNotMislabeled() {
        // 测什么：连接被拒、DNS 失败这类不能被说成超时
        // 怎么算红：什么错都提示「请缩短提示词」—— 服务根本没起来时用户会一直徒劳地删字
        assertFalse(LlmChatClient.isTimeout(new IORuntimeException(new IOException("Connection refused"))));
        assertFalse(LlmChatClient.isTimeout(new IllegalStateException("boom")));
    }
}
