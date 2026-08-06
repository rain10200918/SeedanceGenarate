package org.example.seedancegenarate.stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.example.seedancegenarate.event.TaskStatusChangedEvent;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * SSE 连接管理 + 任务状态推送。替代前端轮询：浏览器用 EventSource 订阅，
 * 后台推进器（{@code VideoTaskPoller}）把任务推进到终态、落库后发事件，这里推给对应用户的所有连接。
 * <p>
 * 每个用户可有多条连接（多标签页）。推送是<b>尽力而为</b>：连接断了就摘除，等前端 EventSource 自动重连
 * 并 refetch 兜底——数据库始终是唯一真相，SSE 只是它上面的加速通知，绝不作为权威来源。
 */
@Slf4j
@Component
public class TaskStreamManager {

    /** 单条连接最长存活 30 分钟；到点前端 EventSource 会自动重连续期。 */
    private static final long EMITTER_TIMEOUT_MS = 30 * 60 * 1000L;

    private final ObjectMapper objectMapper;
    /** userId -> 该用户的所有 SSE 连接 */
    private final Map<Long, Set<SseEmitter>> userEmitters = new ConcurrentHashMap<>();

    public TaskStreamManager(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 为某用户建立一条 SSE 连接并登记；连接结束（完成/超时/出错）时自动摘除。 */
    public SseEmitter subscribe(Long userId) {
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        userEmitters.computeIfAbsent(userId, k -> new CopyOnWriteArraySet<>()).add(emitter);
        emitter.onCompletion(() -> remove(userId, emitter));
        emitter.onTimeout(() -> remove(userId, emitter));
        emitter.onError(e -> remove(userId, emitter));
        // 建连即发一条注释，触发前端 onopen 并穿透部分反向代理的缓冲
        send(userId, emitter, SseEmitter.event().comment("connected"));
        return emitter;
    }

    /**
     * 事务提交后再推：保证只通知已落库的状态；推送失败也不影响业务事务。
     * {@code fallbackExecution=true} 兜底——即便无活动事务也照常推送。
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onStatusChanged(TaskStatusChangedEvent event) {
        Long userId = event.userId();
        if (userId == null) {
            return;
        }
        Set<SseEmitter> set = userEmitters.get(userId);
        if (set == null || set.isEmpty()) {
            return;
        }
        String payload;
        try {
            payload = objectMapper.writeValueAsString(event.message());
        } catch (Exception e) {
            log.warn("序列化任务推送载荷失败: {}", e.getMessage());
            return;
        }
        for (SseEmitter emitter : set) {
            send(userId, emitter, SseEmitter.event().name("task-status").data(payload));
        }
    }

    /** 心跳：定期向所有连接发注释行，防止反向代理 / 浏览器掐断空闲连接。 */
    @Scheduled(fixedDelay = 25 * 1000L)
    public void heartbeat() {
        userEmitters.forEach((userId, set) ->
                set.forEach(emitter -> send(userId, emitter, SseEmitter.event().comment("ping"))));
    }

    /** 统一发送：单连接串行化（SseEmitter 并发 send 不安全），失败即摘除该连接。 */
    private void send(Long userId, SseEmitter emitter, SseEmitter.SseEventBuilder event) {
        try {
            synchronized (emitter) {
                emitter.send(event);
            }
        } catch (IOException | IllegalStateException e) {
            remove(userId, emitter);
        }
    }

    private void remove(Long userId, SseEmitter emitter) {
        Set<SseEmitter> set = userEmitters.get(userId);
        if (set != null) {
            set.remove(emitter);
            if (set.isEmpty()) {
                userEmitters.remove(userId);
            }
        }
    }
}
