package org.example.seedancegenarate.canvas.type;

import com.fasterxml.jackson.databind.JsonNode;
import org.example.seedancegenarate.canvas.CanvasNodeType;
import org.example.seedancegenarate.canvas.MediaType;
import org.example.seedancegenarate.canvas.PortSpec;
import org.example.seedancegenarate.canvas.ResolvedInputs;
import org.example.seedancegenarate.entity.CanvasNode;
import org.example.seedancegenarate.exception.BusinessException;
import org.springframework.stereotype.Component;

/**
 * 素材节点：把素材库里的一张图 / 一段视频 / 一段音频放到画布上，作为下游的输入源。
 * <p>
 * config: {@code {"assetId":123,"mediaType":"IMAGE","url":"..."}}
 * 输出类型 = 素材本身的媒体类型，所以它是唯一能产出 AUDIO 的节点（平台无音频生成模型）。
 */
@Component
public class AssetNodeType implements CanvasNodeType {

    @Override
    public String type() {
        return "ASSET";
    }

    @Override
    public String label() {
        return "素材";
    }

    @Override
    public String description() {
        return "从素材库引入图片 / 视频 / 音频，作为下游节点的输入";
    }

    @Override
    public PortSpec ports(JsonNode config) {
        return PortSpec.sourceOnly(mediaTypeOf(config));
    }

    @Override
    public void validateConfig(JsonNode config) {
        if (config == null || config.path("assetId").isMissingNode() || config.path("assetId").asLong(0) <= 0) {
            throw BusinessException.badRequest("素材节点需要选择一个素材");
        }
        mediaTypeOf(config);
    }

    /** 产物 = 素材本身的地址；没配 url 就还不能给下游用 */
    @Override
    public ResolvedInputs.PortValue output(CanvasNode node, JsonNode config) {
        String url = config == null ? null : config.path("url").asText(null);
        if (url == null || url.isBlank()) {
            return null;
        }
        return new ResolvedInputs.PortValue(mediaTypeOf(config), url);
    }

    /** 未配置时按图片处理：新拖出来的空节点也要能画出连接点 */
    private MediaType mediaTypeOf(JsonNode config) {
        String raw = config == null ? null : config.path("mediaType").asText(null);
        if (raw == null || raw.isBlank()) {
            return MediaType.IMAGE;
        }
        try {
            return MediaType.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw BusinessException.badRequest("素材类型不支持: " + raw);
        }
    }
}
