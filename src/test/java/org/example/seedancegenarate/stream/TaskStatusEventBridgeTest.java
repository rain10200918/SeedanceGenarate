package org.example.seedancegenarate.stream;

import org.example.seedancegenarate.config.DistributedFeatureProperties;
import org.example.seedancegenarate.event.TaskStatusChangedEvent;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaskStatusEventBridgeTest {

    @Test
    void redisModePublishesOnceWithoutDirectLocalDuplicate() {
        DistributedFeatureProperties features = new DistributedFeatureProperties();
        features.setRedisTaskEvents(true);
        TaskStatusEventPublisher publisher = mock(TaskStatusEventPublisher.class);
        when(publisher.publish(event())).thenReturn(true);
        TaskStreamManager streamManager = mock(TaskStreamManager.class);
        TaskStatusEventBridge bridge = new TaskStatusEventBridge(features, publisher, streamManager);

        bridge.onStatusChanged(event());

        verify(publisher).publish(event());
        verify(streamManager, never()).pushLocal(42L, event().message());
    }

    @Test
    void fallsBackToLocalWhenRedisPublishFails() {
        DistributedFeatureProperties features = new DistributedFeatureProperties();
        features.setRedisTaskEvents(true);
        TaskStatusEventPublisher publisher = mock(TaskStatusEventPublisher.class);
        when(publisher.publish(event())).thenReturn(false);
        TaskStreamManager streamManager = mock(TaskStreamManager.class);
        TaskStatusEventBridge bridge = new TaskStatusEventBridge(features, publisher, streamManager);

        bridge.onStatusChanged(event());

        verify(streamManager).pushLocal(42L, event().message());
    }

    @Test
    void disabledRedisModePushesDirectlyLocal() {
        DistributedFeatureProperties features = new DistributedFeatureProperties();
        TaskStatusEventPublisher publisher = mock(TaskStatusEventPublisher.class);
        TaskStreamManager streamManager = mock(TaskStreamManager.class);
        TaskStatusEventBridge bridge = new TaskStatusEventBridge(features, publisher, streamManager);

        bridge.onStatusChanged(event());

        verify(streamManager).pushLocal(42L, event().message());
        verify(publisher, never()).publish(event());
    }

    private TaskStatusChangedEvent event() {
        return new TaskStatusChangedEvent(42L,
                new TaskStatusChangedEvent.Message("tsk_1", "SUCCESS", "tsk_1.mp4",
                        "VIDEO", null, null));
    }
}
