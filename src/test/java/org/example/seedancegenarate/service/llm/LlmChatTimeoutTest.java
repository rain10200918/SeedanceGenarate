package org.example.seedancegenarate.service.llm;

import org.example.seedancegenarate.config.PromptOptimizeConfig;
import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void readTimeoutIsNotFailoverableAndKeepsTheSelfHelpHint() {
        // 测什么：读超时按【类型】认出来（HttpTimeoutException），判为不可切，且保留「请缩短提示词」那句
        // 怎么算红：(a) 把读超时也标成可切 —— 主通道花掉 100s 再切备通道，前端 120s 墙内只剩 20s，
        //          用户多等 20 秒然后照样失败；(b) 用户提示被换成泛泛的「请稍后再试」——
        //          服务根本没问题，是提示词太长，用户不知道删几个字就能过
        LlmChannelException e = LlmChatClient.classify(new HttpTimeoutException("request timed out"));

        assertFalse(e.failoverable(), "读超时不许切下一条");
        assertTrue(e.getMessage().contains("缩短"), "必须保留用户能自救的提示，实际=" + e.getMessage());
    }

    @Test
    void connectTimeoutAndRefusedConnectionAreFastFailuresThatFailOver() {
        // 测什么：连接超时（HttpConnectTimeoutException，是 HttpTimeoutException 的子类）和连接被拒都是快失败，可切
        // 怎么算红：只判父类 HttpTimeoutException —— 连接超时被当成读超时不切，
        //          一台被黑洞的主机让整条降级链永远触发不了，而它 5 秒就能定
        assertTrue(LlmChatClient.classify(new HttpConnectTimeoutException("connect timed out")).failoverable(),
                "连接超时必须可切");
        assertTrue(LlmChatClient.classify(new ConnectException("Connection refused")).failoverable(),
                "连接被拒必须可切");
        assertFalse(LlmChatClient.classify(new ConnectException("Connection refused")).getMessage().contains("缩短"),
                "连接被拒不能被说成超时——服务没起来时用户会一直徒劳地删字");
    }

    @Test
    void authFailuresAreCalledOutAsConfigurationErrors() {
        // 测什么：401/403 的短因里点明「配置错误」，429 点明配额
        // 怎么算红：所有状态码一律「HTTP xxx」—— 密钥填错被当成瞬时故障静默切走，没人去修，
        //          备通道的账单一直涨到有人看账单
        assertTrue(LlmChatClient.httpReason(401).contains("配置错误"));
        assertTrue(LlmChatClient.httpReason(403).contains("配置错误"));
        assertTrue(LlmChatClient.httpReason(429).contains("配额"));
        assertEquals("HTTP 502", LlmChatClient.httpReason(502));
    }
}
