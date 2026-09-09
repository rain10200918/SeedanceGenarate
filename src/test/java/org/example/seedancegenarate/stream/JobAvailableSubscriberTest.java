package org.example.seedancegenarate.stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.seedancegenarate.task.AsyncJobWorkerRuntime;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.DefaultMessage;

import java.nio.charset.StandardCharsets;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class JobAvailableSubscriberTest {

    @Test
    void wakesRuntimeForPipelineJobType() throws Exception {
        AsyncJobWorkerRuntime runtime = mock(AsyncJobWorkerRuntime.class);
        JobAvailableSubscriber subscriber = new JobAvailableSubscriber(new ObjectMapper(), runtime);

        subscriber.onMessage(message("{\"jobType\":\"PIPELINE_NODE_SUBMIT\"}"), null);

        verify(runtime).wake("PIPELINE_NODE_SUBMIT");
    }

    @Test
    void wakesRuntimeForFinalizeJobType() throws Exception {
        AsyncJobWorkerRuntime runtime = mock(AsyncJobWorkerRuntime.class);
        JobAvailableSubscriber subscriber = new JobAvailableSubscriber(new ObjectMapper(), runtime);

        subscriber.onMessage(message("{\"jobType\":\"TASK_FINALIZE\"}"), null);

        verify(runtime).wake("TASK_FINALIZE");
    }

    @Test
    void forwardsGenerationSubmitToTheSingleRuntime() {
        // 【测什么】Subscriber 只把通知交给中央 runtime，不再直调任一 consumer。
        // 【怎么算红】保留旧 consumeNow 分派或漏掉 GENERATION_SUBMIT 时，这条必须变红。
        AsyncJobWorkerRuntime runtime = mock(AsyncJobWorkerRuntime.class);
        JobAvailableSubscriber subscriber = new JobAvailableSubscriber(new ObjectMapper(), runtime);

        subscriber.onMessage(message("{\"jobType\":\"GENERATION_SUBMIT\"}"), null);

        verify(runtime).wake("GENERATION_SUBMIT");
    }

    private org.springframework.data.redis.connection.Message message(String body) {
        return new DefaultMessage("channel".getBytes(StandardCharsets.UTF_8),
                body.getBytes(StandardCharsets.UTF_8));
    }
}
