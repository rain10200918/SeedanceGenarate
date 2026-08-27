package org.example.seedancegenarate.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.seedancegenarate.dto.ApiVideoCreateResponse;
import org.example.seedancegenarate.entity.VideoTask;
import org.example.seedancegenarate.exception.ApiErrorResponse;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 对外 API 的<b>字段名</b>契约：文档承诺什么，序列化就必须吐什么。
 * <p>
 * 2026-08-26 对着代码核 {@code api-docs.md} 时，发现文档在四处描述了并不存在的行为
 * （不存在的 {@code mode} 字段、把文件名说成签名 URL、webhook 示例给完整 OSS 地址、
 * 模型清单少 5 个）。这些都不会有任何测试变红——<b>文档漂移是静默的</b>，
 * 而第三方接入方拿不到别的信息来源，照着写就是错的。
 * <p>
 * 这里只钉住最容易被误写、且第三方一定会解析的字段名。
 */
class ApiContractFieldNamesTest {

    /**
     * 与 Spring Boot 自动配置对齐：注册 JSR-310（Boot 默认会注册），
     * 但<b>不设</b> property-naming-strategy —— 项目里也没设，所以就是默认 camelCase。
     */
    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

    @Test
    void errorBodyUsesRequestIdNotSnakeCase() throws Exception {
        // 【测什么】错误响应里是 requestId（驼峰），因为项目没有配 snake_case 命名策略
        // 【怎么算红】文档写成 request_id —— 第三方按文档取 body["error"]["request_id"]
        //          永远拿到 None，报障时提供不出 request_id，等于失去了唯一的排查线索。
        //          （api-docs.md 与 ApiErrorResponse 的 javadoc 一度都写的是 request_id）
        JsonNode json = objectMapper.valueToTree(new ApiErrorResponse(
                new ApiErrorResponse.ApiError("ARTIFACT_EXPIRED", "视频已过期（仅保留 30 天）", "req_abc")));

        assertTrue(json.path("error").has("requestId"),
                "实际字段=" + json.path("error").fieldNames().next());
        assertFalse(json.path("error").has("request_id"), "没有配 snake_case 策略，不该出现下划线形式");
        assertTrue(json.path("error").has("code"));
        assertTrue(json.path("error").has("message"));
    }

    @Test
    void submitResponseUsesCamelCase() throws Exception {
        // 【测什么】202 响应是 {taskId, status, requestId}
        // 【怎么算红】文档写 {task_id, request_id} —— 第三方取不到 taskId 就无法轮询，
        //          整个接入在第一步就断了
        JsonNode json = objectMapper.valueToTree(
                new ApiVideoCreateResponse("tsk_x", "PROCESSING", "req_x"));

        assertTrue(json.has("taskId"), "实际=" + json);
        assertTrue(json.has("requestId"));
        assertFalse(json.has("task_id"));
    }

    @Test
    void taskResponseExposesArtifactExpiredAndHidesStorageInternals() throws Exception {
        // 【测什么】任务响应下发 artifactExpired，但不下发 OSS 内部字段
        // 【怎么算红】artifactExpired 没被序列化 → 第三方无从判断产物还在不在，
        //          只能等 /content 返 410；反过来若 artifactKey 被下发，
        //          等于把对象存储的内部路径暴露给外部
        VideoTask task = new VideoTask();
        task.setBizTaskId("tsk_x");
        task.setStatus("SUCCESS");
        task.setVideoUrl("tsk_x.mp4");
        task.setArtifactStorageType("OSS");
        task.setArtifactKey("outputs/tsk_x/result.mp4");
        task.setCreateTime(LocalDateTime.now());
        task.setArtifactExpired(true);

        JsonNode json = objectMapper.valueToTree(task);

        assertTrue(json.has("artifactExpired"), "实际=" + json);
        assertTrue(json.path("artifactExpired").asBoolean());
        assertFalse(json.has("artifactKey"), "OSS object key 不能下发给客户端");
        assertFalse(json.has("artifactStorageType"), "存储类型是内部字段");
        assertFalse(json.has("providerTaskId"), "提供方任务 ID 不能下发");
    }

    @Test
    void videoUrlIsAFileNameNotAnAddress() throws Exception {
        // 【测什么】videoUrl 下发的是产物文件名，不是可直接访问的地址
        // 【怎么算红】文档承诺它是「安全签名的 HTTPS 访问链接」（曾经就是这么写的）——
        //          第三方拿去当 URL 请求，得到的是自己域名下的一个相对路径 404；
        //          真正的取件方式是 GET /api/v1/videos/{taskId}/content
        VideoTask task = new VideoTask();
        task.setVideoUrl("tsk_4cbf4eaa.mp4");

        String videoUrl = objectMapper.valueToTree(task).path("videoUrl").asText();

        assertFalse(videoUrl.startsWith("http"), "实际=" + videoUrl
                + "（若这里真的变成了 URL，说明改了行为，文档要跟着改）");
    }
}
