package org.example.seedancegenarate.engine.Impl;

import cn.hutool.crypto.digest.DigestUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.example.seedancegenarate.engine.BillingTiming;
import org.example.seedancegenarate.engine.CompletionMechanism;
import org.example.seedancegenarate.engine.GenerateCommand;
import org.example.seedancegenarate.engine.RemoteStatus;
import org.example.seedancegenarate.engine.SubmitResult;
import org.example.seedancegenarate.engine.VideoEngine;
import org.example.seedancegenarate.config.VideoCompletionProperties;
import org.example.seedancegenarate.engine.comfyui.ComfyUiClient;
import org.example.seedancegenarate.engine.comfyui.ComfyUiNodeScheduler;
import org.example.seedancegenarate.engine.comfyui.ComfyUiProperties;
import org.example.seedancegenarate.engine.ModelSpec;
import org.example.seedancegenarate.engine.comfyui.ReferenceFiles;
import org.example.seedancegenarate.engine.comfyui.WorkflowBuilder;
import org.example.seedancegenarate.entity.VideoTask;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * ComfyUI 引擎（多实例，事件驱动 + 轮询兜底）。
 * 提交：选节点 → 上传参考素材到该节点 → 按 model 选 {@link WorkflowBuilder} 构建工作流 → POST /prompt
 * （配置了回调基址时附带 webhook_url，完成/失败主动回调，零轮询）。
 * 兜底：{@link #poll(VideoTask)} 回到任务记录的同一节点查 /history，供对账任务低频兜底（回调丢失时）。
 */
@Slf4j
@Component
public class ComfyUiEngine implements VideoEngine {

    public static final String PROVIDER = "comfyui";

    private final ComfyUiProperties properties;
    private final ComfyUiClient client;
    private final ComfyUiNodeScheduler scheduler;
    private final Map<String, WorkflowBuilder> builders;
    private final ObjectMapper objectMapper;
    private final VideoCompletionProperties completionProperties;

    public ComfyUiEngine(ComfyUiProperties properties, ComfyUiClient client,
                         ComfyUiNodeScheduler scheduler, List<WorkflowBuilder> builderList,
                         ObjectMapper objectMapper, VideoCompletionProperties completionProperties) {
        this.properties = properties;
        this.client = client;
        this.scheduler = scheduler;
        this.builders = builderList.stream()
                .collect(Collectors.toMap(WorkflowBuilder::model, Function.identity()));
        this.objectMapper = objectMapper;
        this.completionProperties = completionProperties;
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

    /** ComfyUI 支持 webhook 回调：事件驱动（epoll 式），轮询仅作对账兜底 */
    @Override
    public CompletionMechanism completionMechanism() {
        return CompletionMechanism.CALLBACK;
    }

    /** 未配置回调（开发环境）时回退轮询推进，避免任务无回调也无轮询而卡死 */
    @Override
    public boolean needsPolling() {
        return !StringUtils.hasText(completionProperties.getCallbackBaseUrl())
                || !StringUtils.hasText(completionProperties.getCallbackSecret());
    }

    /** 从 ComfyUI 回调提取 prompt_id（execution_success / execution_error 同构） */
    @Override
    public String parseCallbackTaskId(String payload) {
        try {
            JsonNode node = objectMapper.readTree(payload);
            String promptId = node.path("data").path("prompt_id").asText("");
            return promptId.isEmpty() ? null : promptId;
        } catch (Exception e) {
            log.warn("解析 ComfyUI 回调失败: {}", e.getMessage());
            return null;
        }
    }

    /** 回调到达时任务通常已完成：复用 poll 查 /history 拿产物并归一化 */
    @Override
    public RemoteStatus handleCallback(VideoTask task, String payload) throws Exception {
        return poll(task);
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
        List<String> videoUrls = command.getVideoUrls() == null ? Collections.emptyList() : command.getVideoUrls();
        List<String> audioUrls = command.getAudioUrls() == null ? Collections.emptyList() : command.getAudioUrls();
        validate(builder.spec(), imageUrls, videoUrls, audioUrls, command);

        // 1. 选节点
        ComfyUiProperties.Node node = scheduler.pick();
        log.info("ComfyUI 选中节点 {} 处理任务, model={}", node.getId(), command.getModel());

        // 2. 上传参考素材到该节点（各类内顺序保持，对应 <Picture 1..N> / <Video 1..N> / <Audio 1..N>）
        // 文件名内容 hash 化：同素材幂等，防止 ComfyUI input 目录无限增长
        ReferenceFiles files = new ReferenceFiles(
                uploadRefs(node, imageUrls, ".png"),
                uploadRefs(node, videoUrls, ".mp4"),
                uploadRefs(node, audioUrls, ".wav"));

        // 3. 构建工作流并提交（附 webhook_url 时事件驱动，完成/失败主动回调）
        JsonNode workflow = builder.build(command, files);
        String clientId = UUID.randomUUID().toString();
        String promptId = client.submitPrompt(node.getBaseUrl(), workflow, clientId,
                command.getWebhookUrl(), properties.getReadTimeoutMs());

        return SubmitResult.of(promptId, node.getId());
    }

    /** 下载 OSS URL → 上传到节点 input 目录，返回 LoadImage/LoadAudio/XB_VideoLoader 可用的文件名（内容 hash 幂等） */
    private List<String> uploadRefs(ComfyUiProperties.Node node, List<String> urls, String defaultExt) throws Exception {
        List<String> filenames = new ArrayList<>();
        for (String url : urls) {
            byte[] bytes = client.downloadBytes(url);
            String filename = DigestUtil.md5Hex(bytes) + extensionOf(url, defaultExt);
            filenames.add(client.uploadImage(node.getBaseUrl(), bytes, filename, properties.getReadTimeoutMs()));
        }
        return filenames;
    }

    @Override
    public RemoteStatus poll(VideoTask task) throws Exception {
        String nodeId = task.getNodeId();
        if (nodeId == null || nodeId.isBlank()) {
            // 任务尚未完成提交（node_id 由提交链路最后一步回写）：submit 先落库 PROCESSING、
            // 后回写 node_id，poller 可能先扫到这条仍在提交中的任务。此时视为处理中、下一轮再查，
            // 绝不能判失败——那是「节点已被移除」这类配置错误才该报的。
            return RemoteStatus.processing();
        }
        ComfyUiProperties.Node node = properties.findNode(nodeId);
        if (node == null) {
            return RemoteStatus.failed("找不到处理该任务的 ComfyUI 节点: " + nodeId);
        }
        String providerTaskId = task.remoteTaskId();
        JsonNode history = client.getHistory(node.getBaseUrl(), providerTaskId, properties.getReadTimeoutMs());
        JsonNode entry = history.path(providerTaskId);
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

    private void validate(ModelSpec spec, List<String> imageUrls, List<String> videoUrls,
                          List<String> audioUrls, GenerateCommand command) {
        // 多参考模型：图片或视频至少一个（音频单独不算）
        if (spec.needImageOrVideo() && imageUrls.isEmpty() && videoUrls.isEmpty()) {
            throw new RuntimeException("该模型至少需要一个参考图片或参考视频");
        }
        if (!spec.needImageOrVideo() && spec.needImages() && imageUrls.isEmpty()) {
            throw new RuntimeException("该模型需要参考图");
        }
        if (!imageUrls.isEmpty() && (imageUrls.size() < spec.imageMin() || imageUrls.size() > spec.imageMax())) {
            throw new RuntimeException("参考图数量需为 " + spec.imageMin() + "-" + spec.imageMax() + " 张");
        }
        if (!videoUrls.isEmpty() && spec.videoMax() == 0) {
            throw new RuntimeException("该模型不支持参考视频");
        }
        if (spec.videoMax() > 0 && videoUrls.size() > spec.videoMax()) {
            throw new RuntimeException("参考视频数量需为 1-" + spec.videoMax() + " 段");
        }
        if (!audioUrls.isEmpty() && spec.audioMax() == 0) {
            throw new RuntimeException("该模型不支持参考音频");
        }
        if (spec.audioMax() > 0 && audioUrls.size() > spec.audioMax()) {
            throw new RuntimeException("参考音频数量需为 1-" + spec.audioMax() + " 段");
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

    /** 取 URL 的媒体扩展名（图片 / 视频 / 音频），识别不了回退调用方按类型给的默认值 */
    private String extensionOf(String url, String defaultExt) {
        if (url == null) {
            return defaultExt;
        }
        int q = url.indexOf('?');
        String path = q >= 0 ? url.substring(0, q) : url;
        int dot = path.lastIndexOf('.');
        int slash = path.lastIndexOf('/');
        if (dot > slash) {
            String ext = path.substring(dot).toLowerCase();
            if (ext.matches("\\.(png|jpg|jpeg|webp|bmp|gif|mp4|mov|webm|mkv|avi|mp3|wav|m4a|flac|aac|ogg)")) {
                return ext;
            }
        }
        return defaultExt;
    }
}
