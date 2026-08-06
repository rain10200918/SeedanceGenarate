package org.example.seedancegenarate.controller;

import lombok.RequiredArgsConstructor;
import org.example.seedancegenarate.context.UserContext;
import org.example.seedancegenarate.stream.TaskStreamManager;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 任务状态实时推送（SSE）。前端用 EventSource 订阅，替代对 {@code /task/{id}} 的轮询。
 * <p>
 * 鉴权复用 {@code AuthInterceptor}：EventSource 带不了自定义头，故用 {@code ?token=} 查询参数
 * （与本地视频 {@code <video>} 播放一致）。UserContext 在请求线程内解析，转异步前已取到 userId。
 */
@RestController
@RequestMapping("/api/video")
@RequiredArgsConstructor
public class TaskStreamController {
    private final TaskStreamManager taskStreamManager;

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        Long userId = UserContext.requireUserId();
        return taskStreamManager.subscribe(userId);
    }
}
