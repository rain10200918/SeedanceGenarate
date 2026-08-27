package org.example.seedancegenarate.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * 启动时打印一份「生效配置指纹」。
 * <p>
 * <b>为什么值得一条日志</b>：2026-08-26 排查线上问题时，反复卡在同样几个问题上 ——
 * 跑的是哪个构建？事件频道前缀是 {@code prod:} 还是 {@code local:}？分布式锁开没开？
 * ComfyUI 的回调开关是什么？每一个都要么靠 grep 日志猜，要么靠行为反推，各烧掉一轮对话。
 * 这些答案本该在启动的第一秒就摆在那里。
 * <p>
 * <b>绝不打印密钥的值</b>：只报「配没配 + 长度 + SHA-256 前 8 位」。
 * 指纹足够回答「两台实例用的是不是同一个密钥」「轮换生效了没有」，
 * 而拿到指纹推不回原文。今天刚发现回调 token 被明文写进了 docker logs，
 * 这里绝不能再犯第二次。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StartupConfigLogger {

    /**
     * 按<b>最后一段的后缀</b>判定是不是密钥，不是按「包不包含某个词」。
     * <p>
     * 第一版写的是 {@code contains("api-key")}，结果 {@code wechat.pay.api-v3-key}
     * 因为中间夹了 {@code v3} 没匹配上，<b>密钥被明文打了出来</b>——正是这条日志要防的事，
     * 自己先犯了一次。后缀判定同时避免误伤 {@code distributed.lock.key-prefix}
     * 这种「名字里有 key 但不是密钥」的配置。
     */
    private static final List<String> SECRET_SUFFIXES =
            List.of("key", "secret", "token", "password", "passwd", "credential");

    /** 报什么：全是排查时真的会问的问题，不是把配置文件抄一遍 */
    private static final List<String> KEYS = List.of(
            "app.build-ref",
            "spring.datasource.hikari.maximum-pool-size",
            // —— 这四个决定「多实例下会不会互相打架 / 会不会串台」，今天全踩过
            "distributed.lock.enabled",
            "distributed.lock.key-prefix",
            "task-events.channel",
            "async-job.channel",
            "rate-limit.redis-key-prefix",
            "feature.redis-rate-limit",
            "feature.redis-task-events",
            "feature.redis-config-invalidation",
            // —— 任务推进节奏
            "video.default-provider",
            "video.poll.enabled",
            "video.poll.interval-ms",
            "video.poll.round-budget-ms",
            "video.task-timeout-minutes",
            "video.timeout-retry-max",
            "video.reconcile-round-budget-ms",
            // 必须与 OSS 控制台的生命周期规则 outputs-expire 对齐；对不齐时用户会看到
            // 「说没过期但播不了」或「说过期了其实还能播」
            "video.artifact-retention-days",
            // —— ComfyUI：能力声明与超时口径
            "video.comfyui.webhook-supported",
            "video.comfyui.queue-cache-ms",
            "video.comfyui.status-timeout-ms",
            "video.comfyui.read-timeout-ms",
            "video.completion.callback-base-url",
            "video.completion.callback-secret",
            "video.comfyui.access-token",
            // —— 支付
            "wechat.pay.mch-id",
            "wechat.pay.api-v3-key",
            "alipay.app-id",
            // —— 日志
            "mybatis-plus.configuration.log-impl"
    );

    private final Environment environment;

    @EventListener(ApplicationReadyEvent.class)
    public void logEffectiveConfig() {
        StringBuilder sb = new StringBuilder("\n===== 生效配置指纹（密钥只报指纹，不报值）=====");
        for (String key : KEYS) {
            sb.append(String.format("%n  %-42s = %s", key, render(key)));
        }
        sb.append("\n=============================================");
        log.info(sb.toString());
    }

    /** 渲染单个配置项：密钥走指纹，其余原样 */
    String render(String key) {
        String raw = environment.getProperty(key);
        return isSecret(key) ? fingerprint(raw) : display(raw);
    }

    boolean isSecret(String key) {
        String last = key.toLowerCase();
        int dot = last.lastIndexOf('.');
        if (dot >= 0) {
            last = last.substring(dot + 1);
        }
        String segment = last;
        return SECRET_SUFFIXES.stream().anyMatch(segment::endsWith);
    }

    private String display(String value) {
        return value == null ? "<未配置>" : value;
    }

    /** 已配置时报「长度 + SHA-256 前 8 位」：够回答「两边是不是同一个」「轮换生效没」，且推不回原文 */
    private String fingerprint(String value) {
        if (value == null || value.isBlank()) {
            return "<未配置>";
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return "已配置(len=" + value.length() + ", sha256:" + hex + ")";
        } catch (Exception e) {
            return "已配置(len=" + value.length() + ")";
        }
    }
}
