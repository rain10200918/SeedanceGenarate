package org.example.seedancegenarate.controller;

import org.example.seedancegenarate.config.VideoCompletionProperties;
import org.example.seedancegenarate.engine.CompletionMechanism;
import org.example.seedancegenarate.engine.RemoteStatus;
import org.example.seedancegenarate.engine.VideoEngine;
import org.example.seedancegenarate.engine.VideoEngineRegistry;
import org.example.seedancegenarate.entity.VideoTask;
import org.example.seedancegenarate.service.VideoTaskService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaskCallbackControllerTest {

    @Test
    void rejectsCallbackWithWrongToken() throws Exception {
        VideoCompletionProperties properties = new VideoCompletionProperties();
        properties.setCallbackSecret("secret");
        VideoTaskService tasks = mock(VideoTaskService.class);
        TaskCallbackController controller = new TaskCallbackController(
                mock(VideoEngineRegistry.class), tasks, properties);

        ResponseEntity<Void> response = controller.callback("comfyui", "wrong", "{}");

        assertEquals(401, response.getStatusCode().value());
        verify(tasks, never()).updateStatus(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void routesCallbackThroughEngineAndUpdatesTask() throws Exception {
        VideoCompletionProperties properties = new VideoCompletionProperties();
        properties.setCallbackSecret("secret");
        VideoEngine engine = mock(VideoEngine.class);
        when(engine.completionMechanism()).thenReturn(CompletionMechanism.CALLBACK);
        when(engine.parseCallbackTaskId("{\"data\":{\"prompt_id\":\"p1\"}}")).thenReturn("p1");
        VideoTask task = new VideoTask();
        task.setId(7L);
        when(engine.handleCallback(eq(task), anyString())).thenReturn(RemoteStatus.success("http://x/v.mp4"));
        VideoEngineRegistry registry = mock(VideoEngineRegistry.class);
        when(registry.get("comfyui")).thenReturn(engine);
        VideoTaskService tasks = mock(VideoTaskService.class);
        when(tasks.getByProviderTaskId("p1")).thenReturn(task);
        TaskCallbackController controller = new TaskCallbackController(registry, tasks, properties);

        ResponseEntity<Void> response = controller.callback("comfyui", "secret",
                "{\"data\":{\"prompt_id\":\"p1\"}}");

        assertEquals(200, response.getStatusCode().value());
        verify(tasks).updateStatus(task, RemoteStatus.success("http://x/v.mp4"));
    }

    @Test
    void returnsNotFoundWhenTaskMissing() throws Exception {
        VideoCompletionProperties properties = new VideoCompletionProperties();
        properties.setCallbackSecret("secret");
        VideoEngine engine = mock(VideoEngine.class);
        when(engine.parseCallbackTaskId("{}")).thenReturn("p1");
        VideoEngineRegistry registry = mock(VideoEngineRegistry.class);
        when(registry.get("comfyui")).thenReturn(engine);
        VideoTaskService tasks = mock(VideoTaskService.class);
        when(tasks.getByProviderTaskId("p1")).thenReturn(null);
        TaskCallbackController controller = new TaskCallbackController(registry, tasks, properties);

        ResponseEntity<Void> response = controller.callback("comfyui", "secret", "{}");

        assertEquals(404, response.getStatusCode().value());
    }
}
