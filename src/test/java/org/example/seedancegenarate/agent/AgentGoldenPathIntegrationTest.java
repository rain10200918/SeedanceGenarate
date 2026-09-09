package org.example.seedancegenarate.agent;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.example.seedancegenarate.agent.application.*;
import org.example.seedancegenarate.agent.generation.AgentGenerationGateway;
import org.example.seedancegenarate.agent.model.*;
import org.example.seedancegenarate.agent.runtime.*;
import org.example.seedancegenarate.agent.skill.*;
import org.example.seedancegenarate.entity.AsyncJob;
import org.example.seedancegenarate.event.TaskStatusChangedEvent;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Scenario boundary: real application, SQL stores, text skills, Runtime, approval and barrier;
 * scripted Planner/model responses, fake media domain and inherited job_probe queue.
 * This does not certify a provider, wallet, MySQL locking, video prompt preparation or final merge.
 * The delivery here is six five-second clips, NOT an assembled or quality-validated film.
 */
class AgentGoldenPathIntegrationTest extends AgentApprovalIntegrationTest {
    private static final String GOAL = "帮我制作一个30秒校园宣传片，电影感，16:9。";
    private final List<AsyncJob> executed = new ArrayList<>();
    private final Map<String, String> submitted = new LinkedHashMap<>();
    private final Set<String> completedTasks = new HashSet<>();

    // 【测什么】一句目标经过真实文字Skill及Plan采用，六幕一次审批、乱序重复回调跨Barrier自动完成片段集。
    // 【怎么算红】跳过任一文字作品、未批准提交、首幕提前完成、重复Job/事件重复Task或Artifact，事实计数即红。
    @Test void thirtySecondCampusClipSetCompletesAfterOneBatchApprovalAndDuplicateEvents() throws Exception {
        var model = configureScenario();
        app.send(1, conversation, new AgentApplication.Send("goal", GOAL, "local"));
        drain();
        assertEquals("COMPLETED", app.snapshot(1, conversation).turn().status());
        assertEquals(1, artifactCount());
        assertEquals(0, count("agent_plan"), "draft is not an adopted execution plan");

        var session = store.owned(conversation, 1, false);
        var draft = store.artifacts(session).stream().filter(a -> "PLAN".equals(a.type())).findFirst().orElseThrow();
        var workspaceApp = new AgentWorkspaceApplication(store, approvals, tx, json, jobs, model);
        workspaceApp.apply(1, conversation, new AgentWorkspaceApplication.Command("adopt", store.workspace(session).path("version").asLong(),
                "ADOPT_PLAN", new AgentContext.ArtifactRef(draft.id(), draft.version(), null)));
        drain();

        assertEquals("WAITING_APPROVAL", app.snapshot(1, conversation).turn().status());
        assertEquals(1, count("agent_plan"));
        assertEquals(3, count("agent_plan_step"));
        assertEquals(2, db.queryForObject("SELECT COUNT(*) FROM agent_plan_step WHERE status='SUCCEEDED'", Integer.class));
        assertEquals(6, count("agent_plan_scene"));
        assertEquals(3, artifactCount());
        assertEquals(0, count("agent_interaction"), "financial authorization is not a generic question");
        assertEquals(1, count("agent_generation_batch"));
        assertEquals(6, count("agent_approval"));
        assertTrue(submitted.isEmpty(), "preparation cannot purchase media");

        String batchId = db.queryForObject("SELECT id FROM agent_generation_batch", String.class);
        var batch = store.batches().get(batchId);
        var batchApp = new AgentBatchApplication(store, tx, jobs);
        var response = new AgentBatchApplication.Answer("approve-once", batch.version(), batch.binding(), "APPROVE");
        batchApp.answer(1, conversation, batchId, response);
        batchApp.answer(1, conversation, batchId, response);
        drain();
        assertEquals(2, submitted.size(), "bounded parallel submissions");

        // Keep scene one running while later scenes finish; it is the barrier's last dependency.
        for (int ordinal : List.of(2, 3, 4, 5, 6, 1)) {
            String approvalId = db.queryForObject("SELECT approval_id FROM agent_batch_item WHERE batch_id=? AND ordinal_no=?", String.class, batchId, ordinal);
            String task = approvals.get(approvalId).taskId();
            assertNotNull(task, "slot release must submit the next authorized scene");
            completedTasks.add(task);
            var event = new TaskStatusChangedEvent(1L, new TaskStatusChangedEvent.Message(task, "SUCCESS", null, "VIDEO", null, null));
            generation.onTask(event);
            generation.onTask(event);
            drain();
            generation.onTask(event);
            drain();
            if (ordinal != 1) {
                assertEquals("RUNNING", store.batches().get(batchId).status());
                assertEquals("WAITING_TASK", app.snapshot(1, conversation).turn().status());
                assertNotEquals("SUCCEEDED", db.queryForObject("SELECT status FROM agent_plan", String.class));
            }
        }

        assertEquals("SUCCEEDED", store.batches().get(batchId).status());
        assertEquals("COMPLETED", app.snapshot(1, conversation).turn().status());
        assertEquals("SUCCEEDED", db.queryForObject("SELECT status FROM agent_plan", String.class));
        assertEquals(3, db.queryForObject("SELECT COUNT(*) FROM agent_plan_step WHERE status='SUCCEEDED'", Integer.class));
        assertEquals(6, db.queryForObject("SELECT COUNT(*) FROM agent_plan_scene WHERE status='SUCCEEDED'", Integer.class));
        assertEquals(6, db.queryForObject("SELECT COUNT(*) FROM agent_approval WHERE status='SUCCEEDED'", Integer.class));
        assertEquals(6, submitted.size());
        assertEquals(6, new HashSet<>(submitted.values()).size());
        assertEquals(6, db.queryForObject("SELECT COUNT(DISTINCT request_id) FROM agent_approval", Integer.class));
        assertEquals(9, artifactCount()); // Plan + script + storyboard + six clips.
        assertEquals(10, count("agent_skill_call")); // Three text, one batch parent, six children.
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM agent_skill_call WHERE status<>'SUCCEEDED'", Integer.class));
        var board = store.artifacts(store.owned(conversation, 1, false)).stream().filter(a -> "STORYBOARD".equals(a.type())).findFirst().orElseThrow();
        int duration = 0;
        for (var scene : board.data().path("scenes")) duration += scene.path("duration").asInt();
        assertEquals(30, duration);
        var opening = board.data().path("scenes").get(0);
        assertEquals("学生", opening.path("characters").get(0).path("name").asText());
        assertEquals("学生", opening.path("sound").path("dialogue").get(0).path("speaker").asText());
        assertEquals("我们出发吧。", opening.path("sound").path("dialogue").get(0).path("text").asText());
        assertEquals("校门前停步", opening.path("shot").path("endState").asText());

        long decisions = count("agent_decision"), messages = count("conversation_message");
        for (var job : List.copyOf(executed)) execute(job);
        assertEquals(decisions, count("agent_decision"));
        assertEquals(messages, count("conversation_message"));
        assertEquals(9, count("agent_artifact_version"));
        verify(gateway, times(6)).submit(eq(1L), any(), anyString());
        verify(model, times(1)).complete(any(), eq("AGENT_CREATIVE_PLAN"), anyString(), anyString());
        verify(model, times(1)).complete(any(), eq("AGENT_SCRIPT"), anyString(), anyString());
        verify(model, times(1)).complete(any(), eq("AGENT_STORYBOARD"), anyString(), anyString());
    }

    private AgentModelGateway configureScenario() throws Exception {
        runtimeLimits.setBatchEnabled(true);
        var model = mock(AgentModelGateway.class);
        when(model.defaultChannel()).thenReturn("local");
        when(model.channelBinding(anyString())).thenReturn("a".repeat(64));
        when(model.complete(any(), anyString(), anyString(), anyString())).thenAnswer(a -> switch (a.getArgument(1, String.class)) {
            case "AGENT_CREATIVE_PLAN" -> """
                    {"title":"校园宣传片","goal":"30秒校园宣传片，电影感，16:9","constraints":["30秒","电影感","16:9"],"steps":[
                    {"id":"script","kind":"SCRIPT","title":"脚本"},
                    {"id":"board","kind":"STORYBOARD","title":"分镜"},
                    {"id":"video","kind":"VIDEO","title":"六幕视频","scope":"STORYBOARD_SCENES","sourceStepId":"board"}]}
                    """;
            case "AGENT_SCRIPT" -> "{\"title\":\"校园的一天\",\"content\":\"30秒，电影感，16:9。六幕各5秒：校园晨光、课堂、图书馆、实验、体育、夕阳告别。\"}";
            case "AGENT_STORYBOARD" -> storyboard();
            default -> throw new AssertionError("unexpected model scene: " + a.getArgument(1));
        });
        when(skill.descriptor()).thenReturn(new SkillDescriptor("video-generation", "1", "fake video domain boundary", json.createObjectNode(), "VIDEO"));
        quote = videoQuote(json.createObjectNode().put("model", "fake-video").put("prompt", "校园晨光").put("ratio", "16:9").put("duration", 5));
        when(skill.quote(any(), any())).thenReturn(quote);
        when(gateway.videoParameters(any())).thenAnswer(a -> ((ObjectNode)a.getArgument(0)).deepCopy());
        when(gateway.quoteVideo(any(), any())).thenAnswer(a -> videoQuote(a.getArgument(1)));
        when(gateway.submit(anyLong(), any(), anyString())).thenAnswer(a -> {
            TaskQuote q = a.getArgument(1);
            assertEquals(5, q.inputSnapshot().path("duration").asInt());
            assertEquals("16:9", q.inputSnapshot().path("ratio").asText());
            String key = a.getArgument(2);
            assertFalse(submitted.containsKey(key), "duplicate submission must be fenced before domain call");
            String task = "fake-task-" + (submitted.size() + 1);
            submitted.put(key, task);
            return task;
        });
        when(gateway.read(anyLong(), anyString())).thenAnswer(a -> {
            String task = a.getArgument(1);
            return new AgentGenerationGateway.TaskView(task, completedTasks.contains(task) ? "SUCCESS" : "PROCESSING", "VIDEO", null, false, false, null);
        });
        when(planner.decide(any())).thenAnswer(a -> {
            AgentContext c = a.getArgument(0);
            String current = c.plan() == null ? null : c.plan().path("currentStepId").asText(null);
            String id = current == null ? "plan-generation" : switch (current) {
                case "script" -> "script-generation";
                case "board" -> "storyboard-generation";
                case "video" -> "video-generation";
                default -> throw new AssertionError(current);
            };
            if (current == null && c.plan() != null && c.plan().path("confirmed").asBoolean())
                return new AgentDecision("COMPLETE", "六个片段已完成；未合成成片。", null, List.of(), null, null);
            ObjectNode input = json.createObjectNode().put("instruction", GOAL);
            if ("storyboard-generation".equals(id)) {
                var script = c.artifacts().stream().filter(x -> "SCRIPT".equals(x.type())).findFirst().orElseThrow();
                input.putObject("source").put("artifactId", script.id()).put("version", script.version());
            }
            return new AgentDecision("CALL_SKILL", "正在制作", null, List.of(), id,
                    "video-generation".equals(id) ? quote.inputSnapshot() : input);
        });
        runtime = new AgentRuntime(store, jobs, tx, planner, new SkillRegistry(List.of(
                new PlanGenerationSkill(model, json, runtimeLimits), new ScriptGenerationSkill(model, json),
                new StoryboardGenerationSkill(model, json), skill)), json, generation, mock(AgentDecisionDiagnostics.class), model);
        return model;
    }

    private String storyboard() {
        var b = json.createObjectNode().put("title", "校园的一天");
        var scenes = b.putArray("scenes");
        for (int i = 1; i <= 6; i++) scenes.addObject().put("title", "校园片段" + i)
                .put("duration", 5).put("narration", "").put("visual", "电影感，16:9，校园第" + i + "个场景，平稳镜头。");
        var opening = (ObjectNode) scenes.get(0);
        opening.putArray("characters").addObject().put("name", "学生").put("appearance", "黑色短发，精神饱满")
                .put("wardrobe", "蓝白校服，背书包");
        opening.putObject("shot").put("action", "学生走到校门前，回头招手")
                .put("framing", "中景").put("cameraMovement", "缓慢跟拍")
                .put("startState", "学生在校门外行走").put("endState", "校门前停步");
        var sound = opening.putObject("sound").put("narration", "").put("ambience", "清晨鸟鸣与脚步声");
        sound.putArray("dialogue").addObject().put("speaker", "学生").put("text", "我们出发吧。");
        return b.toString();
    }

    private TaskQuote videoQuote(com.fasterxml.jackson.databind.JsonNode input) {
        return new TaskQuote("fake", "fake-video", "测试视频", "VIDEO", input, new BigDecimal("0.10"), "CNY");
    }

    private void drain() {
        int remaining = 60;
        while (db.queryForObject("SELECT COUNT(*) FROM job_probe WHERE done=FALSE", Integer.class) > 0) {
            assertTrue(remaining-- > 0, "scenario exceeded bounded job steps");
            var job = next();
            executed.add(job);
            execute(job);
        }
    }

    private void execute(AsyncJob job) {
        if (AgentGenerationRuntime.JOB.equals(job.getJobType())) generation.execute(job);
        else runtime.execute(job, AgentRuntime.SKILL_JOB.equals(job.getJobType()));
    }

    private long artifactCount() {
        return db.queryForObject("SELECT COUNT(DISTINCT artifact_id) FROM agent_artifact_version", Long.class);
    }
}
