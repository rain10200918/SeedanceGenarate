package org.example.seedancegenarate.agent.generation;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.example.seedancegenarate.context.UserContext;
import org.example.seedancegenarate.exception.BusinessException;
import org.example.seedancegenarate.service.ArtifactStorage;
import org.springframework.web.bind.annotation.*;
import java.time.Duration;

/** Every media fetch rechecks owner/moderation/expiry before a short-lived OSS redirect. */
@RestController
@RequestMapping("/api/agent/media")
@RequiredArgsConstructor
public class AgentMediaController {
    private final AgentGenerationGateway gateway;
    private final ArtifactStorage storage;
    @GetMapping("/{taskId}")
    public void media(@PathVariable String taskId,HttpServletResponse response) throws Exception {
        long user=UserContext.requireUserId();
        String key=gateway.mediaKey(user,taskId);
        response.setHeader("Cache-Control","no-store");
        response.sendRedirect(storage.createSignedGetUrl(key,Duration.ofSeconds(60)));
    }
}
