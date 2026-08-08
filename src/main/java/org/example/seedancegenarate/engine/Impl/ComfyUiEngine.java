package org.example.seedancegenarate.engine.Impl;

import cn.hutool.crypto.digest.DigestUtil;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.example.seedancegenarate.engine.BillingTiming;
import org.example.seedancegenarate.engine.GenerateCommand;
import org.example.seedancegenarate.engine.RemoteStatus;
import org.example.seedancegenarate.engine.SubmitResult;
import org.example.seedancegenarate.engine.VideoEngine;
import org.example.seedancegenarate.engine.comfyui.ComfyUiClient;
import org.example.seedancegenarate.engine.comfyui.ComfyUiNodeScheduler;
import org.example.seedancegenarate.engine.comfyui.ComfyUiProperties;
import org.example.seedancegenarate.engine.ModelSpec;
import org.example.seedancegenarate.engine.comfyui.WorkflowBuilder;
import org.example.seedancegenarate.entity.VideoTask;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * ComfyUI 引擎（多实例，提交-轮询模型）。
 * 提交：选节点 → 上传参考图到该节点 → 按 model 选 {@link WorkflowBuilder} 构建工作流 → POST /prompt。
 * 轮询：回到任务记录的同一节点查 /history，翻译成归一化的 {@link RemoteStatus}。
 */
@Slf4j
@Component
public class ComfyUiEngine implements VideoEngine {

    public static final String PROVIDER = "comfyui";

    private final ComfyUiProperties properties;
    private final ComfyUiClient client;
    private final ComfyUiNodeScheduler scheduler;
    private final Map<String, WorkflowBuilder> builders;

    public ComfyUiEngine(ComfyUiProperties properties, ComfyUiClient client,
                         ComfyUiNodeScheduler scheduler, List<WorkflowBuilder> builderList) {
        this.properties = properties;
        this.client = client;
        this.scheduler = scheduler;
        this.builders = builderList.stream()
                .collect(Collectors.toMap(WorkflowBuilder::model, Function.identity()));
    }

    @Override
    public String provider() {
        return PROVIDER;
    }

    /** 自建 ComfyUI：仅在生成成功时计费 */
    @Override
    public BillingTiming billingTiming() {
        return BillingTiming.ON_SUCCESS;
    }

    @Override
    public String displayName() {
        return "ComfyUI（自建）";
    }

    @Override
    public List<ModelSpec> models() {
        return builders.values().stream()
                .map(WorkflowBuilder::spec)
                .toList();
    }

    @Override
    public SubmitResult submit(GenerateCommand command) throws Exception {
        WorkflowBuilder builder = resolveBuilder(command.getModel());
        List<String> imageUrls = command.getImageUrls() == null ? Collections.emptyList() : command.getImageUrls();
        validate(builder.spec(), imageUrls, command);

        // 1. 选节点
        ComfyUiProperties.Node node = scheduler.pick();
        log.info("ComfyUI 选中节点 {} 处理任务, model={}", node.getId(), command.getModel());

        // 2. 上传参考图到该节点（顺序保持，对应 <Picture 1..N>）
        // 文件名内容 hash 化：同图幂等，防止 ComfyUI input 目录无限增长
        List<String> filenames = new ArrayList<>();
        for (String url : imageUrls) {
            byte[] bytes = client.downloadBytes(url);
            String filename = DigestUtil.md5Hex(bytes) + extensionOf(url);
            filenames.add(client.uploadImage(node.getBaseUrl(), bytes, filename, properties.getReadTimeoutMs()));
        }

        // 3. 构建工作流并提交
        JsonNode workflow = builder.build(command, filenames);
        String clientId = UUID.randomUUID().toString();
        String promptId = client.submitPrompt(node.getBaseUrl(), workflow, clientId, properties.getReadTimeoutMs());

        return SubmitResult.of(promptId, node.getId());
    }

    @Override
    public RemoteStatus poll(VideoTask task) throws Exception {
        ComfyUiProperties.Node node = properties.findNode(task.getNodeId());
        if (node == null) {
            return RemoteStatus.failed("找不到处理该任务的 ComfyUI 节点: " + task.getNodeId());
        }
        JsonNode history = client.getHistory(node.getBaseUrl(), task.getTaskId(), properties.getReadTimeoutMs());
        JsonNode entry = history.path(task.getTaskId());
        if (entry.isMissingNode() || entry.isEmpty()) {
            return RemoteStatus.processing();   // 尚未进入 history，仍在排队 / 执行
        }
        JsonNode status = entry.path("status");
        String statusStr = status.path("status_str").asText("");
        if ("error".equals(statusStr)) {
            return RemoteStatus.failed(extractError(status));
        }
        boolean completed = status.path("completed").asBoolean(false) || "success".equals(statusStr);
        if (!completed) {
            return RemoteStatus.processing();
        }
        String videoUrl = extractVideoUrl(node.getBaseUrl(), entry.path("outputs"));
        return videoUrl == null ? RemoteStatus.failed("任务完成但未找到视频输出") : RemoteStatus.success(videoUrl);
    }

    private WorkflowBuilder resolveBuilder(String model) {
        if (model == null || model.isBlank()) {
            throw new RuntimeException("ComfyUI 生成必须指定 model");
        }
        WorkflowBuilder builder = builders.get(model);
        if (builder == null) {
            throw new RuntimeException("不支持的 ComfyUI 模型: " + model);
        }
        return builder;
    }

    private void validate(ModelSpec spec, List<String> imageUrls, GenerateCommand command) {
        if (spec.needImages() && imageUrls.isEmpty()) {
            throw new RuntimeException("该模型需要参考图");
        }
        if (!imageUrls.isEmpty() && (imageUrls.size() < spec.imageMin() || imageUrls.size() > spec.imageMax())) {
            throw new RuntimeException("参考图数量需为 " + spec.imageMin() + "-" + spec.imageMax() + " 张");
        }
        if (command.getRatio() != null && !spec.ratios().isEmpty() && !spec.ratios().contains(command.getRatio())) {
            throw new RuntimeException("该模型不支持的比例: " + command.getRatio());
        }
    }

    /** 从 outputs 找第一个视频文件（VHS_VideoCombine 输出在 gifs / videos 下） */
    private String extractVideoUrl(String baseUrl, JsonNode outputs) {
        if (!outputs.isObject()) {
            return null;
        }
        for (JsonNode nodeOutput : outputs) {
            for (String key : new String[]{"gifs", "videos", "images"}) {
                JsonNode arr = nodeOutput.path(key);
                if (arr.isArray() && !arr.isEmpty()) {
                    JsonNode file = arr.get(0);
                    String filename = file.path("filename").asText("");
                    if (filename.isEmpty()) {
                        continue;
                    }
                    return client.buildViewUrl(baseUrl, filename,
                            file.path("subfolder").asText(""),
                            file.path("type").asText("output"));
                }
            }
        }
        return null;
    }

    private String extractError(JsonNode status) {
        JsonNode messages = status.path("messages");
        if (messages.isArray()) {
            for (JsonNode m : messages) {
                if (m.isArray() && m.size() >= 2) {
                    String exc = m.get(1).path("exception_message").asText("");
                    if (!exc.isEmpty()) {
                        return exc;
                    }
                }
            }
        }
        return "ComfyUI 执行失败";
    }

    private String extensionOf(String url) {
        if (url == null) {
            return ".png";
        }
        int q = url.indexOf('?');
        String path = q >= 0 ? url.substring(0, q) : url;
        int dot = path.lastIndexOf('.');
        int slash = path.lastIndexOf('/');
        if (dot > slash) {
            String ext = path.substring(dot).toLowerCase();
            if (ext.matches("\\.(png|jpg|jpeg|webp|bmp|gif)")) {
                return ext;
            }
        }
        return ".png";
    }
}
