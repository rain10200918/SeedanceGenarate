package org.example.seedancegenarate.canvas;

import java.util.List;
import java.util.Map;

/**
 * 某个节点各输入端口上已解析出的上游产物（按连线顺序）。
 * 执行时据此装配 {@code SubmitRequest} 的 imageUrls / videoUrls / audioUrls / prompt。
 */
public record ResolvedInputs(Map<String, List<PortValue>> byPort) {

    public List<PortValue> of(String portId) {
        return byPort.getOrDefault(portId, List.of());
    }

    /** 端口上的一个值：媒体类型 + 地址（TEXT 时 url 为内容本身） */
    public record PortValue(MediaType mediaType, String value) {
    }
}
