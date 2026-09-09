package org.example.seedancegenarate.service.Impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.seedancegenarate.entity.Pipeline;
import org.example.seedancegenarate.entity.PipelineNode;
import org.example.seedancegenarate.entity.UserAsset;
import org.example.seedancegenarate.entity.VideoTask;
import org.example.seedancegenarate.mapper.PipelineMapper;
import org.example.seedancegenarate.mapper.PipelineNodeMapper;
import org.example.seedancegenarate.mapper.UserAssetMapper;
import org.example.seedancegenarate.service.AsyncJobService;
import org.example.seedancegenarate.service.VideoSubmitService;
import org.example.seedancegenarate.service.VideoTaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PipelineGenerationFenceTest {

    private PipelineMapper pipelines;
    private PipelineNodeMapper nodes;
    private UserAssetMapper assets;
    private VideoSubmitService submits;
    private AsyncJobService jobs;
    private VideoTaskService videoTasks;
    private PipelineServiceImpl service;
    private Pipeline pipeline;

    @BeforeEach
    void setUp() {
        pipelines = mock(PipelineMapper.class);
        nodes = mock(PipelineNodeMapper.class);
        assets = mock(UserAssetMapper.class);
        submits = mock(VideoSubmitService.class);
        jobs = mock(AsyncJobService.class);
        videoTasks = mock(VideoTaskService.class);
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        when(transactions.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
        service = new PipelineServiceImpl(pipelines, nodes, assets, submits, new ObjectMapper(),
                jobs, videoTasks, Runnable::run, transactions);
        pipeline = new Pipeline();
        pipeline.setId(20L);
        pipeline.setUserId(7L);
        pipeline.setStatus("RUNNING");
        pipeline.setProvider("seedance");
        pipeline.setModel("m1");
        when(pipelines.selectById(20L)).thenReturn(pipeline);
    }

    @Test
    void staleTerminalEventCannotOverwriteNewPipelineGeneration() {
        // 【测什么】旧 task 事件 SELECT 后节点已 rerun，finish CAS=0 时不刷新流水线。
        // 【怎么算红】按主键 updateById 或忽略 rows，会把新一轮节点写成旧终态。
        PipelineNode node = node("PROCESSING", "req-new", "task-old");
        when(nodes.selectOne(any())).thenReturn(node);
        when(nodes.finishTaskIfCurrent(10L, "task-old", "SUCCESS", "old.mp4", ""))
                .thenReturn(0);

        service.applyTaskFinished("task-old", "SUCCESS", "old.mp4", null);

        verify(nodes).finishTaskIfCurrent(10L, "task-old", "SUCCESS", "old.mp4", "");
        verify(pipelines, never()).updateById(any(Pipeline.class));
        verify(nodes, never()).updateById(any(PipelineNode.class));
    }

    @Test
    void reconcileCatchesUpTerminalTaskAfterLateTaskLink() {
        // 【测什么】PROCESSING+taskId 的流水线节点若已终态，对账用 taskId CAS 补回填并汇总。
        // 【怎么算红】reconcile 对 PROCESSING 一律跳过时，丢过一次事件的节点永久卡住。
        PipelineNode node = node("PROCESSING", "req-1", "task-1");
        when(nodes.selectList(any())).thenReturn(List.of(node));
        when(nodes.selectOne(any())).thenReturn(node);
        when(nodes.finishTaskIfCurrent(10L, "task-1", "SUCCESS", "result.mp4", ""))
                .thenReturn(1);
        VideoTask finished = new VideoTask();
        finished.setBizTaskId("task-1");
        finished.setStatus("SUCCESS");
        finished.setVideoUrl("result.mp4");
        when(videoTasks.getOne(any(), anyBoolean())).thenReturn(finished);

        service.reconcileRunning(20L);

        verify(nodes).finishTaskIfCurrent(10L, "task-1", "SUCCESS", "result.mp4", "");
        verify(pipelines).updateById(pipeline);
        assertEquals("DONE", pipeline.getStatus());
    }

    @Test
    void jobDrivenRetryStartsFreshGenerationAndOnlyEnqueues() throws Exception {
        // 【测什么】job-driven retry 原子清旧 taskId/换 requestId 后只入队，不在请求线程 submit。
        // 【怎么算红】恢复同步 occupy→submit 会重新打开“任务已建、节点未补链且无 job”的宕机窗。
        PipelineNode failed = node("FAILED", "req-old", "task-old");
        when(nodes.selectById(10L)).thenReturn(failed);
        when(nodes.beginRun(eq(10L), eq("FAILED"), anyString())).thenReturn(1);
        ReflectionTestUtils.setField(service, "jobDriven", true);

        service.retryNode(7L, 20L, 10L);

        verify(nodes).beginRun(eq(10L), eq("FAILED"), anyString());
        verify(jobs).enqueue(eq("PIPELINE_NODE_SUBMIT"),
                contains("pipeline:20:node:10:request:pipeline:10:"), anyString());
        verify(submits, never()).submit(any());
        assertEquals("PENDING", failed.getStatus());
        assertNull(failed.getTaskId());
    }

    @Test
    void legacyNonJobDrivenSubmitCreatesGenerationAndLinksWithRequestFence() throws Exception {
        // 【测什么】灰度开关关闭时，无 requestId 的旧节点仍可开始代际、提交并用 requestId CAS 补链。
        // 【怎么算红】新 overload 对 null requestId 直接 return 会让旧模式完全不提交。
        PipelineNode pending = node("PENDING", null, null);
        pending.setPrompt("prompt");
        pending.setAssetIds("[3]");
        when(nodes.selectById(10L)).thenReturn(pending);
        when(nodes.beginRun(eq(10L), eq("PENDING"), anyString())).thenReturn(1);
        when(nodes.occupyForSubmit(eq(10L), anyString())).thenReturn(1);
        UserAsset asset = new UserAsset();
        asset.setId(3L);
        asset.setUserId(7L);
        asset.setStatus("ACTIVE");
        asset.setUrl("https://cdn.invalid/ref.png");
        when(assets.selectBatchIds(any())).thenReturn(List.of(asset));
        VideoTask created = new VideoTask();
        created.setBizTaskId("task-new");
        when(submits.submit(any())).thenReturn(created);
        ArgumentCaptor<String> requestId = ArgumentCaptor.forClass(String.class);

        service.submitNodeForJob(10L);

        verify(nodes).linkTaskIfMissing(eq(10L), requestId.capture(), eq("task-new"));
        assertTrue(requestId.getValue().startsWith("pipeline:10:"));
    }

    @Test
    void nodeSqlClearsOldTaskAndFencesTerminalEvent() throws Exception {
        // 【测什么】新代际 SQL 清 task_id，终态 SQL 同时要求 PROCESSING+旧 taskId。
        // 【怎么算红】任一条件缺失，旧提交/旧终态都可能串入新一轮。
        String begin = sql("beginRun", Long.class, String.class, String.class);
        String finish = sql("finishTaskIfCurrent", Long.class, String.class,
                String.class, String.class, String.class);

        assertTrue(begin.contains("task_id = NULL"), begin);
        assertTrue(begin.contains("submit_request_id = #{requestId}"), begin);
        assertTrue(finish.contains("status = 'PROCESSING'"), finish);
        assertTrue(finish.contains("task_id = #{taskId}"), finish);
    }

    private String sql(String method, Class<?>... parameters) {
        var reflected = org.springframework.util.ReflectionUtils.findMethod(
                PipelineNodeMapper.class, method, parameters);
        var update = org.springframework.core.annotation.AnnotatedElementUtils.findMergedAnnotation(
                reflected, org.apache.ibatis.annotations.Update.class);
        return String.join(" ", update.value()).replaceAll("\\s+", " ");
    }

    private PipelineNode node(String status, String requestId, String taskId) {
        PipelineNode node = new PipelineNode();
        node.setId(10L);
        node.setPipelineId(20L);
        node.setKind("SCENE");
        node.setSeq(1);
        node.setStatus(status);
        node.setSubmitRequestId(requestId);
        node.setTaskId(taskId);
        return node;
    }
}
