package org.example.seedancegenarate.engine.Impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.seedancegenarate.config.SeedanceConfig;
import org.example.seedancegenarate.engine.GenerateCommand;
import org.example.seedancegenarate.engine.ModelSpec;
import org.example.seedancegenarate.engine.RemoteStatus;
import org.example.seedancegenarate.engine.SubmitResult;
import org.example.seedancegenarate.engine.VideoEngine;
import org.example.seedancegenarate.entity.VideoTask;
import org.example.seedancegenarate.service.SeedanceService;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * Seedance（火山方舟）引擎。当前作为适配层，内部复用既有 {@link SeedanceService} 的 HTTP 调用，
 * 对外只暴露与提供方无关的 {@link VideoEngine} 契约；私有返回格式在此翻译成 {@link RemoteStatus}。
 * <p>
 * 模型：注册标识（{@code id}，走 /options 与模型开放闸门）与方舟 API 模型名（{@code name}，请求 body）
 * 是两个名字，由 {@code seedance.models} 配置建立映射。未配置时回退单模型
 * {@code id:"seedance" → name: seedance.model}。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeedanceEngine implements VideoEngine {

    public static final String PROVIDER = "seedance";

    private static final List<String> RATIOS = List.of("16:9", "9:16", "1:1", "4:3", "3:4");
    private static final List<Integer> DURATIONS = List.of(5, 8, 10, 15);

    private final SeedanceService seedanceService;
    private final SeedanceConfig seedanceConfig;
    private final ObjectMapper objectMapper;

    @Override
    public String provider() {
        return PROVIDER;
    }

    @Override
    public String displayName() {
        return "Seedance（云端）";
    }

    @Override
    public List<ModelSpec> models() {
        return effectiveModels().stream()
                .map(m -> new ModelSpec(
                        PROVIDER, m.getId(),
                        m.getLabel() == null || m.getLabel().isBlank() ? m.getId() : m.getLabel(),
                        false, 0, 9, RATIOS, 5, 15, DURATIONS))
                .toList();
    }

    @Override
    public SubmitResult submit(GenerateCommand command) throws Exception {
        // 请求里的 model 是注册标识，先解析成方舟 API 模型名；标识不认则拒绝（不落库前由闸门拦，此处兜底）
        String apiModelName = resolveApiModelName(command.getModel());
        List<String> imageUrls = command.getImageUrls() == null
                ? Collections.emptyList()
                : command.getImageUrls();
        String taskId = seedanceService.generate(
                imageUrls,
                command.getPrompt(),
                command.getDuration(),
                command.getRatio(),
                apiModelName
        );
        // Seedance 为云端服务，无节点亲和概念
        return SubmitResult.of(taskId);
    }

    @Override
    public RemoteStatus poll(VideoTask task) throws Exception {
        Object result = seedanceService.getTask(task.remoteTaskId());
        JsonNode node = objectMapper.valueToTree(result);
        String status = node.path("status").asText();
        if ("succeeded".equals(status)) {
            return RemoteStatus.success(node.path("content").path("video_url").asText());
        }
        if ("failed".equals(status)) {
            return RemoteStatus.failed(node.path("error").path("message").asText());
        }
        return RemoteStatus.processing();
    }

    /** 配置的模型列表；未配置时回退单模型（id="seedance" → name=seedance.model）。 */
    private List<SeedanceConfig.SeedanceModel> effectiveModels() {
        List<SeedanceConfig.SeedanceModel> models = seedanceConfig.getModels();
        if (models == null || models.isEmpty()) {
            SeedanceConfig.SeedanceModel single = new SeedanceConfig.SeedanceModel();
            single.setId(PROVIDER);
            single.setName(seedanceConfig.getModel());
            single.setLabel("Seedance 默认");
            return List.of(single);
        }
        return models;
    }

    /** 注册标识 → 方舟 API 模型名；标识不存在抛错（与 ComfyUI 的 resolveBuilder 同一风格）。 */
    private String resolveApiModelName(String modelId) {
        String name = effectiveModels().stream()
                .filter(m -> modelId != null && modelId.equals(m.getId()))
                .map(SeedanceConfig.SeedanceModel::getName)
                .findFirst()
                .orElse(null);
        if (name == null || name.isBlank()) {
            throw new RuntimeException("不支持的 Seedance 模型: " + modelId);
        }
        return name;
    }
}
