package org.example.seedancegenarate.canvas;

import java.util.List;

/**
 * 一个节点当前的端口形状。**由节点配置推导，不是写死的**——
 * 生成节点换个模型，imageMax/videoMax/audioMax 变了，端口就跟着变。
 *
 * @param output 该节点产物的类型；null = 不产出（如纯输入型节点亦可有输出，见各实现）
 * @param inputs 输入端口列表；空 = 源节点（素材/文本）
 */
public record PortSpec(MediaType output, List<InputPort> inputs) {

    public static PortSpec sourceOnly(MediaType output) {
        return new PortSpec(output, List.of());
    }

    /** 按端口 id 查；找不到返回 null（连线校验据此判定「端口不存在」） */
    public InputPort input(String portId) {
        return inputs.stream().filter(p -> p.id().equals(portId)).findFirst().orElse(null);
    }
}
