package org.example.seedancegenarate.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.seedancegenarate.config.VideoCompletionProperties;
import org.example.seedancegenarate.engine.RemoteStatus;
import org.example.seedancegenarate.engine.VideoEngine;
import org.example.seedancegenarate.engine.VideoEngineRegistry;
import org.example.seedancegenarate.entity.VideoTask;
import org.example.seedancegenarate.service.VideoTaskService;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 提供方任务完成回调统一入口（事件驱动引擎）。
 * <p>
 * POST /api/callback/{provider}?token=xxx
 * 校验 secret（防伪造回调）→ 引擎解析远端任务 ID → 反查任务 → 引擎归一化状态 → 统一落库。
 * 该路径不经过登录鉴权（提供方无用户凭证），必须依赖 token 校验。
 */
@Slf4j
@RestController
@RequestMapping("/api/callback")
@RequiredArgsConstructor
public class TaskCallbackController {
    private final VideoEngineRegistry videoEngineRegistry;
    private final VideoTaskService videoTaskService;
    private final VideoCompletionProperties properties;

    @PostMapping("/{provider}")
    public ResponseEntity<Void> callback(
            @PathVariable String provider,
            @RequestParam("token") String token,
            @RequestBody String body) {
        // 防伪造：token 与配置的 secret 严格相等（常量时间比较）
        String secret = properties.getCallbackSecret();
        if (!StringUtils.hasText(secret) || !constantTimeEquals(secret, token)) {
            return ResponseEntity.status(401).build();
        }
        VideoEngine engine = videoEngineRegistry.get(provider);
        if (engine == null) {
            log.warn("收到未知提供方回调: provider={}", provider);
            return ResponseEntity.notFound().build();
        }
        try {
            String providerTaskId = engine.parseCallbackTaskId(body);
            if (!StringUtils.hasText(providerTaskId)) {
                log.warn("回调无法解析任务 ID: provider={}", provider);
                return ResponseEntity.badRequest().build();
            }
            VideoTask task = videoTaskService.getByProviderTaskId(providerTaskId);
            if (task == null) {
                log.warn("回调对应任务不存在: provider={}, providerTaskId={}", provider, providerTaskId);
                return ResponseEntity.notFound().build();
            }
            log.info("收到提供方回调: provider={}, providerTaskId={}, bizTaskId={}",
                    provider, providerTaskId, task.businessTaskId());
            RemoteStatus status = engine.handleCallback(task, body);
            videoTaskService.updateStatus(task, status);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            log.warn("回调处理失败: provider={}, reason={}", provider, e.getMessage());
            return ResponseEntity.status(500).build();
        }
    }

    private boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        byte[] x = a.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] y = b.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (x.length != y.length) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < x.length; i++) {
            diff |= x[i] ^ y[i];
        }
        return diff == 0;
    }
}
