package org.example.seedancegenarate.canvas.type;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.example.seedancegenarate.canvas.CanvasNodeType;
import org.example.seedancegenarate.canvas.InputPort;
import org.example.seedancegenarate.canvas.MediaType;
import org.example.seedancegenarate.canvas.PortSpec;
import org.example.seedancegenarate.canvas.ResolvedInputs;
import org.example.seedancegenarate.canvas.SubmitPlan;
import org.example.seedancegenarate.entity.CanvasNode;
import org.example.seedancegenarate.engine.ModelSpec;
import org.example.seedancegenarate.engine.OutputType;
import org.example.seedancegenarate.engine.VideoEngine;
import org.example.seedancegenarate.engine.VideoEngineRegistry;
import org.example.seedancegenarate.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 生成节点：一个节点 = 一次生成任务。
 * <p>
 * <b>端口不是写死的，是从所选模型的 {@link ModelSpec} 推导出来的</b>：
 * 输出类型看 {@code outputType}，图片/视频/音频输入口的数量看 {@code imageMax/videoMax/audioMax}
 * （为 0 的端口根本不出现），必填看 {@code needImages/imageMin}。换模型端口就变。
 * <p>
 * config: {@code {"provider":"seedance","model":"seedance","prompt":"...","duration":5,
 * "ratio":"16:9","megapixels":null}}
 */
@Component
@RequiredArgsConstructor
public class GenerateNodeType implements CanvasNodeType {

    /** 只做读解析，ObjectMapper 线程安全，复用一个即可 */
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private final VideoEngineRegistry videoEngineRegistry;

    @Value("${video.default-provider:seedance}")
    private String defaultProvider;

    @Override
    public String type() {
        return "GENERATE";
    }

    @Override
    public String label() {
        return "生成";
    }

    @Override
    public String description() {
        return "调用模型生成图片或视频；输入端口随所选模型的能力变化";
    }

    @Override
    public PortSpec ports(JsonNode config) {
        ModelSpec spec = findSpec(config);
        if (spec == null) {
            // 还没选模型（刚拖出来）或模型已下架：只留提示词口，媒体口等选定模型后再出现
            return new PortSpec(MediaType.VIDEO, List.of(
                    new InputPort(InputPort.PROMPT, "提示词", MediaType.TEXT, false, 1)));
        }

        List<InputPort> inputs = new ArrayList<>();
        inputs.add(new InputPort(InputPort.PROMPT, "提示词", MediaType.TEXT, false, 1));
        if (spec.imageMax() > 0) {
            // 必填口径与提交侧一致：needImages 或「图片/视频二选一」都要求至少接一个图
            boolean required = spec.needImages();
            inputs.add(new InputPort(InputPort.IMAGE, "参考图", MediaType.IMAGE, required, spec.imageMax()));
        }
        if (spec.videoMax() > 0) {
            inputs.add(new InputPort(InputPort.VIDEO, "参考视频", MediaType.VIDEO, false, spec.videoMax()));
        }
        if (spec.audioMax() > 0) {
            inputs.add(new InputPort(InputPort.AUDIO, "参考音频", MediaType.AUDIO, false, spec.audioMax()));
        }

        MediaType output = spec.outputType() == OutputType.IMAGE ? MediaType.IMAGE : MediaType.VIDEO;
        return new PortSpec(output, inputs);
    }

    @Override
    public void validateConfig(JsonNode config) {
        String model = text(config, "model");
        if (model == null) {
            return; // 允许存草稿：没选模型的节点可以保存，只是运行时会被就绪校验挡住
        }
        if (findSpec(config) == null) {
            throw BusinessException.badRequest("模型不可用: " + model);
        }
    }

    @Override
    public boolean executable() {
        return true;
    }

    /** 找不到模型时端口退化，这里给出明确的运行期错误（比提交后失败更早暴露） */
    @Override
    public String readinessError(CanvasNode node, JsonNode config, ResolvedInputs inputs) {
        if (text(config, "model") == null) {
            return "请先为该节点选择模型";
        }
        if (findSpec(config) == null) {
            return "该节点使用的模型已不可用，请重新选择";
        }
        return CanvasNodeType.super.readinessError(node, config, inputs);
    }

    /**
     * 产物 = 生成结果。只有 SUCCESS 且 output 里有地址时才向下游提供 ——
     * 这是「上游没跑完，下游拿不到东西」在数据层面的体现。
     */
    @Override
    public ResolvedInputs.PortValue output(CanvasNode node, JsonNode config) {
        if (!"SUCCESS".equals(node.getStatus()) || node.getOutput() == null) {
            return null;
        }
        try {
            JsonNode out = JSON.readTree(node.getOutput());
            String url = out.path("url").asText(null);
            String media = out.path("mediaType").asText(null);
            if (url == null || url.isBlank()) {
                return null;
            }
            MediaType type = media == null ? MediaType.VIDEO : MediaType.valueOf(media.toUpperCase());
            return new ResolvedInputs.PortValue(type, url);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 把「配置 + 各端口上游产物」翻译成一次生成请求。
     * prompt 口接来的文本追加在本节点提示词之后（文本节点用于复用公共风格描述）。
     */
    @Override
    public SubmitPlan plan(CanvasNode node, JsonNode config, ResolvedInputs inputs) {
        StringBuilder prompt = new StringBuilder();
        String own = text(config, "prompt");
        if (own != null) {
            prompt.append(own);
        }
        for (ResolvedInputs.PortValue value : inputs.of(InputPort.PROMPT)) {
            if (prompt.length() > 0) {
                prompt.append('\n');
            }
            prompt.append(value.value());
        }

        return new SubmitPlan(
                text(config, "provider"),
                text(config, "model"),
                prompt.toString(),
                urls(inputs, InputPort.IMAGE),
                urls(inputs, InputPort.VIDEO),
                urls(inputs, InputPort.AUDIO),
                config == null || config.path("duration").isMissingNode()
                        ? null : config.path("duration").asInt(),
                text(config, "ratio"),
                config == null || config.path("megapixels").isNull()
                        || config.path("megapixels").isMissingNode()
                        ? null : config.path("megapixels").asDouble());
    }

    private List<String> urls(ResolvedInputs inputs, String portId) {
        return inputs.of(portId).stream().map(ResolvedInputs.PortValue::value).toList();
    }

    /** 按 config 里的 provider/model 查能力；查不到（未选/已下架）返回 null，调用方负责降级 */
    private ModelSpec findSpec(JsonNode config) {
        String model = text(config, "model");
        if (model == null) {
            return null;
        }
        String provider = text(config, "provider");
        try {
            VideoEngine engine = videoEngineRegistry.get(provider == null ? defaultProvider : provider);
            return engine.models().stream()
                    .filter(m -> m.model().equals(model))
                    .findFirst()
                    .orElse(null);
        } catch (RuntimeException e) {
            return null; // 提供方本身不存在，等同模型不可用
        }
    }

    private String text(JsonNode config, String field) {
        if (config == null) {
            return null;
        }
        String value = config.path(field).asText(null);
        return (value == null || value.isBlank()) ? null : value;
    }
}
