package org.example.seedancegenarate.stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.seedancegenarate.task.PipelineNodeSubmitConsumer;
import org.example.seedancegenarate.task.TaskFinalizeConsumer;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.DefaultMessage;

import java.nio.charset.StandardCharsets;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class JobAvailableSubscriberTest {

    @Test
    void wakesPipelineConsumerForPipelineJobType() throws Exception {
        PipelineNodeSubmitConsumer pipeline = mock(PipelineNodeSubmitConsumer.class);
        TaskFinalizeConsumer finalize = mock(TaskFinalizeConsumer.class);
        JobAvailableSubscriber subscriber = new JobAvailableSubscriber(new ObjectMapper(), pipeline, finalize);

        subscriber.onMessage(message("{\"jobType\":\"PIPELINE_NODE_SUBMIT\"}"), null);

        verify(pipeline).consumeNow();
        verify(finalize, never()).consumeNow();
    }

    @Test
    void wakesFinalizeConsumerForFinalizeJobType() throws Exception {
        PipelineNodeSubmitConsumer pipeline = mock(PipelineNodeSubmitConsumer.class);
        TaskFinalizeConsumer finalize = mock(TaskFinalizeConsumer.class);
        JobAvailableSubscriber subscriber = new JobAvailableSubscriber(new ObjectMapper(), pipeline, finalize);

        subscriber.onMessage(message("{\"jobType\":\"TASK_FINALIZE\"}"), null);

        verify(finalize).consumeNow();
        verify(pipeline, never()).consumeNow();
    }

    private org.springframework.data.redis.connection.Message message(String body) {
        return new DefaultMessage("channel".getBytes(StandardCharsets.UTF_8),
                body.getBytes(StandardCharsets.UTF_8));
    }
}
