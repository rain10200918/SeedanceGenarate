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
 * 文本节点：一段可复用的提示词片段，连到多个生成节点的 prompt 口。
 * <p>
 * config: {@code {"content":"赛博朋克，雨夜，霓虹"}}
 */
@Component
public class TextNodeType implements CanvasNodeType {

    private static final int MAX_LENGTH = 4000;

    @Override
    public String type() {
        return "TEXT";
    }

    @Override
    public String label() {
        return "文本";
    }

    @Override
    public String description() {
        return "可复用的提示词片段，可同时接入多个生成节点";
    }

    @Override
    public PortSpec ports(JsonNode config) {
        return PortSpec.sourceOnly(MediaType.TEXT);
    }

    /** 产物 = 文本内容本身（不是地址）；空内容不向下游提供 */
    @Override
    public ResolvedInputs.PortValue output(CanvasNode node, JsonNode config) {
        String content = config == null ? null : config.path("content").asText(null);
        if (content == null || content.isBlank()) {
            return null;
        }
        return new ResolvedInputs.PortValue(MediaType.TEXT, content);
    }

    @Override
    public void validateConfig(JsonNode config) {
        String content = config == null ? null : config.path("content").asText(null);
        if (content != null && content.length() > MAX_LENGTH) {
            throw BusinessException.badRequest("文本节点内容过长（上限 " + MAX_LENGTH + " 字）");
        }
    }
}
