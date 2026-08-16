package org.example.seedancegenarate.stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.seedancegenarate.task.PipelineNodeSubmitConsumer;
import org.example.seedancegenarate.task.TaskFinalizeConsumer;
import org.example.seedancegenarate.task.TaskRetryConsumer;
import org.example.seedancegenarate.service.Impl.VideoTaskServiceImpl;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

/** 订阅作业可用通知，按作业类型唤醒对应消费 Worker（立即消费一轮）。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JobAvailableSubscriber implements MessageListener {
    private final ObjectMapper objectMapper;
    private final PipelineNodeSubmitConsumer pipelineNodeSubmitConsumer;
    private final TaskFinalizeConsumer taskFinalizeConsumer;
    private final TaskRetryConsumer taskRetryConsumer;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            JsonNode json = objectMapper.readTree(message.getBody());
            String jobType = json.path("jobType").asText("");
            log.info("收到作业可用通知: jobType={}", jobType);
            if ("PIPELINE_NODE_SUBMIT".equals(jobType)) {
                pipelineNodeSubmitConsumer.consumeNow();
            } else if (VideoTaskServiceImpl.JOB_TYPE_TASK_FINALIZE.equals(jobType)) {
                taskFinalizeConsumer.consumeNow();
            } else if (VideoTaskServiceImpl.JOB_TYPE_TASK_RETRY.equals(jobType)) {
                taskRetryConsumer.consumeNow();
            }
        } catch (Exception e) {
            log.warn("解析作业通知失败: reason={}", e.getMessage());
        }
    }
}
