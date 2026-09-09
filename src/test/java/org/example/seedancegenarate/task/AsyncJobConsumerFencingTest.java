package org.example.seedancegenarate.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.seedancegenarate.entity.AsyncJob;
import org.example.seedancegenarate.entity.Canvas;
import org.example.seedancegenarate.entity.CanvasNode;
import org.example.seedancegenarate.entity.Pipeline;
import org.example.seedancegenarate.entity.PipelineNode;
import org.example.seedancegenarate.entity.VideoTask;
import org.example.seedancegenarate.mapper.CanvasMapper;
import org.example.seedancegenarate.mapper.CanvasNodeMapper;
import org.example.seedancegenarate.mapper.PipelineMapper;
import org.example.seedancegenarate.mapper.PipelineNodeMapper;
import org.example.seedancegenarate.service.AsyncJobService;
import org.example.seedancegenarate.service.CanvasRunService;
import org.example.seedancegenarate.service.PipelineService;
import org.example.seedancegenarate.service.VideoSubmitService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AsyncJobConsumerFencingTest {

    @Test
    // 【测什么】画布提交的旧 lease 已丢失时，不能把新 Worker 的节点覆盖成 FAILED。
    // 【怎么算红】把 canvas 节点 FAILED 更新移到 fenced failAndRetry 之前，这条必须变红。
    void staleCanvasLeaseCannotOverwriteNodeState() throws Exception {
        AsyncJobService jobs = mock(AsyncJobService.class);
        CanvasRunService canvas = mock(CanvasRunService.class);
        CanvasNodeMapper nodes = mock(CanvasNodeMapper.class);
        AsyncJob job = claimed("CANVAS_NODE_SUBMIT", canvasPayload());
        when(nodes.occupyForSubmit(10L, "canvas:req-1")).thenReturn(1);
        when(nodes.selectById(10L)).thenReturn(canvasNode(10L, 20L, "canvas:req-1"));
        doThrow(new IllegalStateException("submit failed")).when(canvas)
                .submitNodeForJob(10L, "canvas:req-1");
        when(jobs.failAndRetry(job, "submit failed")).thenReturn(false);
        CanvasNodeSubmitConsumer consumer = new CanvasNodeSubmitConsumer(
                jobs, canvas, nodes, mock(CanvasMapper.class), mock(VideoSubmitService.class),
                new ObjectMapper(), transactionTemplate());

        consumer.execute(job);

        verify(jobs).failAndRetry(job, "submit failed");
        verify(nodes, never()).updateById(any(CanvasNode.class));
        verify(nodes, never()).releaseForRetryIfUnlinked(10L, "canvas:req-1", "submit failed");
    }

    @Test
    // 【测什么】流水线提交的旧 lease 已丢失时，不能把新 Worker 的节点覆盖成 FAILED。
    // 【怎么算红】把 pipeline 节点 FAILED 更新移到 fenced failAndRetry 之前，这条必须变红。
    void stalePipelineLeaseCannotOverwriteNodeState() throws Exception {
        AsyncJobService jobs = mock(AsyncJobService.class);
        PipelineService pipeline = mock(PipelineService.class);
        PipelineNodeMapper nodes = mock(PipelineNodeMapper.class);
        AsyncJob job = claimed("PIPELINE_NODE_SUBMIT", pipelinePayload());
        when(nodes.occupyForSubmit(10L, "pipeline:req-1")).thenReturn(1);
        when(nodes.selectById(10L)).thenReturn(pipelineNode(10L, 20L, "pipeline:req-1"));
        doThrow(new IllegalStateException("submit failed")).when(pipeline)
                .submitNodeForJob(10L, "pipeline:req-1");
        when(jobs.failAndRetry(job, "submit failed")).thenReturn(false);
        PipelineNodeSubmitConsumer consumer = new PipelineNodeSubmitConsumer(
                jobs, pipeline, nodes, mock(PipelineMapper.class), mock(VideoSubmitService.class),
                new ObjectMapper(), transactionTemplate());
        ReflectionTestUtils.setField(consumer, "jobDriven", true);

        consumer.execute(job);

        verify(jobs).failAndRetry(job, "submit failed");
        verify(nodes, never()).updateById(any(PipelineNode.class));
        verify(nodes, never()).releaseForRetryIfUnlinked(10L, "pipeline:req-1", "submit failed");
    }

    @Test
    // 【测什么】fenced fail 已执行但节点条件回退落库异常时，两者所在事务必须回滚。
    // 【怎么算红】把 failAndRetry 与节点代际 CAS 拆到 TransactionTemplate 外，这条必须变红。
    void nodeWriteExceptionRollsBackTheFencedFailureTransition() throws Exception {
        AsyncJobService jobs = mock(AsyncJobService.class);
        CanvasRunService canvas = mock(CanvasRunService.class);
        CanvasNodeMapper nodes = mock(CanvasNodeMapper.class);
        AsyncJob job = claimed("CANVAS_NODE_SUBMIT", canvasPayload());
        when(nodes.occupyForSubmit(10L, "canvas:req-1")).thenReturn(1);
        doThrow(new IllegalStateException("submit failed")).when(canvas)
                .submitNodeForJob(10L, "canvas:req-1");
        when(jobs.failAndRetry(job, "submit failed")).thenReturn(true);
        CanvasNode current = canvasNode(10L, 20L, "canvas:req-1");
        when(nodes.selectById(10L)).thenReturn(current);
        when(nodes.releaseForRetryIfUnlinked(10L, "canvas:req-1", "submit failed"))
                .thenThrow(new IllegalStateException("write failed"));
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        TransactionStatus status = mock(TransactionStatus.class);
        when(manager.getTransaction(any(TransactionDefinition.class))).thenReturn(status);
        CanvasNodeSubmitConsumer consumer = new CanvasNodeSubmitConsumer(
                jobs, canvas, nodes, mock(CanvasMapper.class), mock(VideoSubmitService.class),
                new ObjectMapper(), new TransactionTemplate(manager));

        assertThrows(IllegalStateException.class, () -> consumer.execute(job));

        verify(jobs).failAndRetry(job, "submit failed");
        verify(nodes).releaseForRetryIfUnlinked(10L, "canvas:req-1", "submit failed");
        verify(manager).rollback(status);
    }

    @Test
    // 【测什么】旧 Worker 死在 task 落库后，画布节点重领作业能按持久化 requestId 补回 taskId。
    // 【怎么算红】occupy=0 仍直接 complete、或补链没带 requestId 代际栅栏时，这条变红。
    void occupiedCanvasNodeRecoversTaskLinkByRequestId() throws Exception {
        AsyncJobService jobs = mock(AsyncJobService.class);
        CanvasRunService canvasRuns = mock(CanvasRunService.class);
        CanvasNodeMapper nodes = mock(CanvasNodeMapper.class);
        CanvasMapper canvases = mock(CanvasMapper.class);
        VideoSubmitService submits = mock(VideoSubmitService.class);
        AsyncJob job = claimed("CANVAS_NODE_SUBMIT", canvasPayload());
        when(nodes.occupyForSubmit(10L, "canvas:req-1")).thenReturn(0);
        CanvasNode node = canvasNode(10L, 20L, "canvas:req-1");
        when(nodes.selectById(10L)).thenReturn(node);
        Canvas canvas = new Canvas();
        canvas.setId(20L);
        canvas.setUserId(30L);
        when(canvases.selectById(20L)).thenReturn(canvas);
        VideoTask task = queuedTask("tsk_canvas");
        when(submits.findByRequestId(30L, "canvas:req-1")).thenReturn(task);
        when(nodes.linkTaskIfMissing(10L, "canvas:req-1", "tsk_canvas")).thenReturn(1);
        CanvasNodeSubmitConsumer consumer = new CanvasNodeSubmitConsumer(
                jobs, canvasRuns, nodes, canvases, submits,
                new ObjectMapper(), transactionTemplate());

        consumer.execute(job);

        verify(nodes).linkTaskIfMissing(10L, "canvas:req-1", "tsk_canvas");
        verify(jobs).complete(job);
        verify(jobs, never()).failAndRetry(eq(job), any(String.class));
        verify(canvasRuns, never()).submitNodeForJob(any(Long.class), any(String.class));
    }

    @Test
    // 【测什么】流水线节点同样能恢复链接，不能因为 occupy=0 丢掉其提交作业。
    // 【怎么算红】只修画布、未修流水线时，这条变红。
    void occupiedPipelineNodeRecoversTaskLinkByRequestId() throws Exception {
        AsyncJobService jobs = mock(AsyncJobService.class);
        PipelineService pipelineRuns = mock(PipelineService.class);
        PipelineNodeMapper nodes = mock(PipelineNodeMapper.class);
        PipelineMapper pipelines = mock(PipelineMapper.class);
        VideoSubmitService submits = mock(VideoSubmitService.class);
        AsyncJob job = claimed("PIPELINE_NODE_SUBMIT", pipelinePayload());
        when(nodes.occupyForSubmit(10L, "pipeline:req-1")).thenReturn(0);
        PipelineNode node = pipelineNode(10L, 20L, "pipeline:req-1");
        when(nodes.selectById(10L)).thenReturn(node);
        Pipeline pipeline = new Pipeline();
        pipeline.setId(20L);
        pipeline.setUserId(30L);
        when(pipelines.selectById(20L)).thenReturn(pipeline);
        VideoTask task = queuedTask("tsk_pipeline");
        when(submits.findByRequestId(30L, "pipeline:req-1")).thenReturn(task);
        when(nodes.linkTaskIfMissing(10L, "pipeline:req-1", "tsk_pipeline")).thenReturn(1);
        PipelineNodeSubmitConsumer consumer = new PipelineNodeSubmitConsumer(
                jobs, pipelineRuns, nodes, pipelines, submits,
                new ObjectMapper(), transactionTemplate());
        ReflectionTestUtils.setField(consumer, "jobDriven", true);

        consumer.execute(job);

        verify(nodes).linkTaskIfMissing(10L, "pipeline:req-1", "tsk_pipeline");
        verify(jobs).complete(job);
        verify(jobs, never()).failAndRetry(eq(job), any(String.class));
        verify(pipelineRuns, never()).submitNodeForJob(any(Long.class), any(String.class));
    }

    @Test
    // 【测什么】升级前只有 nodeId 的画布 job 可读取当前 requestId 并按同一栅栏补链。
    // 【怎么算红】新 Consumer 直接 complete legacy payload 时，旧版本宕机窗里的节点永久 PROCESSING。
    void legacyCanvasPayloadCanRecoverCurrentGeneration() throws Exception {
        AsyncJobService jobs = mock(AsyncJobService.class);
        CanvasRunService runs = mock(CanvasRunService.class);
        CanvasNodeMapper nodes = mock(CanvasNodeMapper.class);
        CanvasMapper canvases = mock(CanvasMapper.class);
        VideoSubmitService submits = mock(VideoSubmitService.class);
        AsyncJob job = claimed("CANVAS_NODE_SUBMIT", "{\"canvasNodeId\":10}");
        CanvasNode node = canvasNode(10L, 20L, "canvas:req-current");
        when(nodes.selectById(10L)).thenReturn(node);
        when(nodes.occupyForSubmit(10L, "canvas:req-current")).thenReturn(0);
        Canvas canvas = new Canvas();
        canvas.setId(20L);
        canvas.setUserId(30L);
        when(canvases.selectById(20L)).thenReturn(canvas);
        when(submits.findByRequestId(30L, "canvas:req-current"))
                .thenReturn(queuedTask("tsk_canvas"));
        when(nodes.linkTaskIfMissing(10L, "canvas:req-current", "tsk_canvas")).thenReturn(1);
        CanvasNodeSubmitConsumer consumer = new CanvasNodeSubmitConsumer(
                jobs, runs, nodes, canvases, submits, new ObjectMapper(), transactionTemplate());

        consumer.execute(job);

        verify(nodes).occupyForSubmit(10L, "canvas:req-current");
        verify(nodes).linkTaskIfMissing(10L, "canvas:req-current", "tsk_canvas");
        verify(jobs).complete(job);
    }

    @Test
    // 【测什么】升级前只有 nodeId 的流水线 job 同样可接管当前 requestId。
    // 【怎么算红】只兼容画布会让旧流水线 PROCESSING+空 taskId 永久卡住。
    void legacyPipelinePayloadCanRecoverCurrentGeneration() throws Exception {
        AsyncJobService jobs = mock(AsyncJobService.class);
        PipelineService runs = mock(PipelineService.class);
        PipelineNodeMapper nodes = mock(PipelineNodeMapper.class);
        PipelineMapper pipelines = mock(PipelineMapper.class);
        VideoSubmitService submits = mock(VideoSubmitService.class);
        AsyncJob job = claimed("PIPELINE_NODE_SUBMIT", "{\"pipelineNodeId\":10}");
        PipelineNode node = pipelineNode(10L, 20L, "pipeline:req-current");
        when(nodes.selectById(10L)).thenReturn(node);
        when(nodes.occupyForSubmit(10L, "pipeline:req-current")).thenReturn(0);
        Pipeline pipeline = new Pipeline();
        pipeline.setId(20L);
        pipeline.setUserId(30L);
        when(pipelines.selectById(20L)).thenReturn(pipeline);
        when(submits.findByRequestId(30L, "pipeline:req-current"))
                .thenReturn(queuedTask("tsk_pipeline"));
        when(nodes.linkTaskIfMissing(10L, "pipeline:req-current", "tsk_pipeline")).thenReturn(1);
        PipelineNodeSubmitConsumer consumer = new PipelineNodeSubmitConsumer(
                jobs, runs, nodes, pipelines, submits, new ObjectMapper(), transactionTemplate());
        ReflectionTestUtils.setField(consumer, "jobDriven", true);

        consumer.execute(job);

        verify(nodes).occupyForSubmit(10L, "pipeline:req-current");
        verify(nodes).linkTaskIfMissing(10L, "pipeline:req-current", "tsk_pipeline");
        verify(jobs).complete(job);
    }

    @Test
    // 【测什么】旧 requestId 的 submit job 遇到新 generation 时直接收旧 job，不碰当前节点。
    // 【怎么算红】consumer 按 nodeId 占位/提交会让旧 job 使用新节点内容重复创建任务。
    void staleCanvasGenerationJobCannotSubmitCurrentNode() throws Exception {
        AsyncJobService jobs = mock(AsyncJobService.class);
        CanvasRunService runs = mock(CanvasRunService.class);
        CanvasNodeMapper nodes = mock(CanvasNodeMapper.class);
        AsyncJob job = claimed("CANVAS_NODE_SUBMIT", canvasPayload());
        when(nodes.occupyForSubmit(10L, "canvas:req-1")).thenReturn(0);
        when(nodes.selectById(10L)).thenReturn(canvasNode(10L, 20L, "canvas:req-2"));
        CanvasNodeSubmitConsumer consumer = new CanvasNodeSubmitConsumer(
                jobs, runs, nodes, mock(CanvasMapper.class), mock(VideoSubmitService.class),
                new ObjectMapper(), transactionTemplate());

        consumer.execute(job);

        verify(jobs).complete(job);
        verify(runs, never()).submitNodeForJob(any(Long.class), any(String.class));
        verify(nodes, never()).releaseForRetryIfUnlinked(any(), any(), any());
        verify(nodes, never()).linkTaskIfMissing(any(), any(), any());
    }

    @Test
    // 【测什么】requestId 对应任务尚不可见时，fenced retry 后必须把本代空 taskId 节点退回可再占位状态。
    // 【怎么算红】只 failAndRetry 却留节点 PROCESSING，下一租约 occupy 永返 0，这条必须变红。
    void missingRecoveredCanvasTaskReleasesCurrentGenerationForRetry() {
        AsyncJobService jobs = mock(AsyncJobService.class);
        CanvasRunService canvasRuns = mock(CanvasRunService.class);
        CanvasNodeMapper nodes = mock(CanvasNodeMapper.class);
        CanvasMapper canvases = mock(CanvasMapper.class);
        VideoSubmitService submits = mock(VideoSubmitService.class);
        AsyncJob job = claimed("CANVAS_NODE_SUBMIT", canvasPayload());
        when(nodes.occupyForSubmit(10L, "canvas:req-1")).thenReturn(0);
        when(nodes.selectById(10L)).thenReturn(canvasNode(10L, 20L, "canvas:req-1"));
        Canvas canvas = new Canvas();
        canvas.setId(20L);
        canvas.setUserId(30L);
        when(canvases.selectById(20L)).thenReturn(canvas);
        when(submits.findByRequestId(30L, "canvas:req-1")).thenReturn(null);
        when(jobs.failAndRetry(job, "画布节点生成任务关联尚不可见")).thenReturn(true);
        when(nodes.releaseForRetryIfUnlinked(
                10L, "canvas:req-1", "画布节点生成任务关联尚不可见")).thenReturn(1);
        CanvasNodeSubmitConsumer consumer = new CanvasNodeSubmitConsumer(
                jobs, canvasRuns, nodes, canvases, submits,
                new ObjectMapper(), transactionTemplate());

        consumer.execute(job);

        verify(jobs).failAndRetry(job, "画布节点生成任务关联尚不可见");
        verify(nodes).releaseForRetryIfUnlinked(
                10L, "canvas:req-1", "画布节点生成任务关联尚不可见");
        verify(jobs, never()).complete(job);
        verify(nodes, never()).linkTaskIfMissing(any(Long.class), any(String.class), any(String.class));
    }

    @Test
    // 【测什么】流水线的恢复 miss 走同一套“租约栅栏 + requestId 代际栅栏”回退。
    // 【怎么算红】只修画布，或用 updateById 无条件覆盖新一轮运行，这条必须变红。
    void missingRecoveredPipelineTaskReleasesCurrentGenerationForRetry() {
        AsyncJobService jobs = mock(AsyncJobService.class);
        PipelineService pipelineRuns = mock(PipelineService.class);
        PipelineNodeMapper nodes = mock(PipelineNodeMapper.class);
        PipelineMapper pipelines = mock(PipelineMapper.class);
        VideoSubmitService submits = mock(VideoSubmitService.class);
        AsyncJob job = claimed("PIPELINE_NODE_SUBMIT", pipelinePayload());
        when(nodes.occupyForSubmit(10L, "pipeline:req-1")).thenReturn(0);
        when(nodes.selectById(10L)).thenReturn(pipelineNode(10L, 20L, "pipeline:req-1"));
        Pipeline pipeline = new Pipeline();
        pipeline.setId(20L);
        pipeline.setUserId(30L);
        when(pipelines.selectById(20L)).thenReturn(pipeline);
        when(submits.findByRequestId(30L, "pipeline:req-1")).thenReturn(null);
        when(jobs.failAndRetry(job, "流水线节点生成任务关联尚不可见")).thenReturn(true);
        when(nodes.releaseForRetryIfUnlinked(
                10L, "pipeline:req-1", "流水线节点生成任务关联尚不可见")).thenReturn(1);
        PipelineNodeSubmitConsumer consumer = new PipelineNodeSubmitConsumer(
                jobs, pipelineRuns, nodes, pipelines, submits,
                new ObjectMapper(), transactionTemplate());
        ReflectionTestUtils.setField(consumer, "jobDriven", true);

        consumer.execute(job);

        verify(jobs).failAndRetry(job, "流水线节点生成任务关联尚不可见");
        verify(nodes).releaseForRetryIfUnlinked(
                10L, "pipeline:req-1", "流水线节点生成任务关联尚不可见");
        verify(jobs, never()).complete(job);
        verify(nodes, never()).linkTaskIfMissing(any(Long.class), any(String.class), any(String.class));
    }

    private CanvasNode canvasNode(Long id, Long canvasId, String requestId) {
        CanvasNode node = new CanvasNode();
        node.setId(id);
        node.setCanvasId(canvasId);
        node.setStatus("PROCESSING");
        node.setSubmitRequestId(requestId);
        return node;
    }

    private PipelineNode pipelineNode(Long id, Long pipelineId, String requestId) {
        PipelineNode node = new PipelineNode();
        node.setId(id);
        node.setPipelineId(pipelineId);
        node.setStatus("PROCESSING");
        node.setSubmitRequestId(requestId);
        return node;
    }

    private VideoTask queuedTask(String taskId) {
        VideoTask task = new VideoTask();
        task.setBizTaskId(taskId);
        task.setTaskId(taskId);
        task.setStatus("PROCESSING");
        task.setPhase("QUEUED");
        task.setCurrentAttemptId(99L);
        return task;
    }

    private AsyncJob claimed(String jobType, String payload) {
        AsyncJob job = new AsyncJob();
        job.setId(1L);
        job.setJobType(jobType);
        job.setPayload(payload);
        job.setStatus(AsyncJob.STATUS_RUNNING);
        job.setAttempts(0);
        job.setMaxAttempts(5);
        job.setLeaseToken("old-token");
        job.setLeaseGeneration(1L);
        return job;
    }

    private String canvasPayload() {
        return "{\"canvasNodeId\":10,\"expectedRequestId\":\"canvas:req-1\"}";
    }

    private String pipelinePayload() {
        return "{\"pipelineNodeId\":10,\"expectedRequestId\":\"pipeline:req-1\"}";
    }

    private TransactionTemplate transactionTemplate() {
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        TransactionStatus status = mock(TransactionStatus.class);
        when(manager.getTransaction(any(TransactionDefinition.class))).thenReturn(status);
        return new TransactionTemplate(manager);
    }
}
