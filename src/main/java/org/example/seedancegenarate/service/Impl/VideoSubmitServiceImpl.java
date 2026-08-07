package org.example.seedancegenarate.service.Impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.example.seedancegenarate.context.UserContext;
import org.example.seedancegenarate.engine.GenerateCommand;
import org.example.seedancegenarate.engine.GenerationMode;
import org.example.seedancegenarate.engine.OutputType;
import org.example.seedancegenarate.engine.SubmitResult;
import org.example.seedancegenarate.engine.VideoEngine;
import org.example.seedancegenarate.engine.VideoEngineRegistry;
import org.example.seedancegenarate.entity.VideoTask;
import org.example.seedancegenarate.event.TaskSubmittedEvent;
import org.example.seedancegenarate.service.CostRecordService;
import org.example.seedancegenarate.service.ModelAccessService;
import org.example.seedancegenarate.service.VideoSubmitService;
import org.example.seedancegenarate.service.VideoTaskService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

/**
 * 提交编排实现。从 {@code VideoController} 提取（UI/API 共用）：
 * 解析实际生效模型 → 开放闸门 → 落库 → 引擎提交 → 回写 → 提交即计费。
 */
@Service
@RequiredArgsConstructor
public class VideoSubmitServiceImpl implements VideoSubmitService {

    private final VideoEngineRegistry videoEngineRegistry;
    private final VideoTaskService videoTaskService;
    private final CostRecordService costRecordService;
    private final ModelAccessService modelAccessService;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher applicationEventPublisher;

    /** 默认提供方；请求未显式指定 provider 时使用 */
    @Value("${video.default-provider:seedance}")
    private String defaultProvider;

    @Override
    public void validate(String provider, String model) {
        VideoEngine engine = videoEngineRegistry.get(resolveProvider(provider));
        assertModelOpen(engine.effectiveModel(model));
    }

    @Override
    public VideoTask submit(SubmitRequest request) throws Exception {
        String provider = resolveProvider(request.provider());
        VideoEngine engine = videoEngineRegistry.get(provider);
        // 闸门基于「实际生效的模型」而非请求原始值（防不传/乱传 model 绕过）
        String effectiveModel = engine.effectiveModel(request.model());
        assertModelOpen(effectiveModel);

        // 统一默认值（与 UI 控制器一致）：API 可能不传 duration/ratio，而 ComfyUI builder 的
        // Map.of 查找不接受 null key（2026-08-06 实测 NPE），空值必须先归一
        Integer duration = request.duration() == null ? 8 : request.duration();
        String ratio = (request.ratio() == null || request.ratio().isBlank()) ? "16:9" : request.ratio();

        // 任务类型 = (有无参考图) × (模型输出类型)
        List<String> imageUrls = request.imageUrls() == null ? Collections.emptyList() : request.imageUrls();
        boolean hasImages = !imageUrls.isEmpty();
        OutputType outputType = engine.outputType(effectiveModel);
        GenerationMode mode = GenerationMode.of(hasImages, outputType);

        VideoTask task = new VideoTask();
        task.setUserId(request.userId());
        task.setPrompt(request.prompt());
        task.setImages(hasImages ? objectMapper.writeValueAsString(imageUrls) : null);
        task.setDuration(duration);
        task.setRatio(ratio);
        task.setStatus("PROCESSING");
        task.setProvider(provider);
        task.setModel(effectiveModel);
        task.setOutputType(outputType.name());
        task.setApiKeyId(request.apiKeyId());
        videoTaskService.save(task);

        GenerateCommand command = GenerateCommand.builder()
                .mode(mode)
                .imageUrls(imageUrls)
                .prompt(request.prompt())
                .duration(duration)
                .ratio(ratio)
                .model(effectiveModel)
                .megapixels(request.megapixels())
                .build();
        SubmitResult submit = engine.submit(command);
        task.setTaskId(submit.getProviderTaskId());
        task.setNodeId(submit.getNodeId());
        videoTaskService.updateById(task);
        // 提交即计费仅对 ON_SUBMIT 提供方生效（如 Seedance），幂等。
        // 原由控制器 AOP 切面触发，现收进共享提交路径——UI 与对外 API 两条入口都走这里，都会计费。
        costRecordService.recordOnSubmit(task);
        // 任务提交成功事件：素材库等下游通过监听器解耦登记，不阻塞提交链路（@Async）
        applicationEventPublisher.publishEvent(new TaskSubmittedEvent(request.userId(), task.getTaskId(), imageUrls));
        return task;
    }

    private String resolveProvider(String provider) {
        return (provider == null || provider.isBlank()) ? defaultProvider : provider.trim();
    }

    /**
     * 提交校验：普通用户不得使用未开放的模型（前端已过滤，此处后端硬拦，防手拼请求）。
     * 入参必须是 {@link VideoEngine#effectiveModel(String)} 解析后的实际生效模型。
     */
    private void assertModelOpen(String model) {
        if (!UserContext.isAdmin() && !modelAccessService.isOpen(model)) {
            throw new RuntimeException("该模型未开放");
        }
    }
}
