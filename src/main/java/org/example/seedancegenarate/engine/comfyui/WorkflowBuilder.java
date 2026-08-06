package org.example.seedancegenarate.engine.comfyui;

import com.fasterxml.jackson.databind.JsonNode;
import org.example.seedancegenarate.engine.GenerateCommand;
import org.example.seedancegenarate.engine.ModelSpec;

import java.util.List;

/**
 * ComfyUI 工作流构建策略：每个模型 / 工作流一份，由 {@link } 按 {@link #model()} 选择。
 * 新增一个可选模型 = 新增一个实现类，其余零改动。
 */
public interface WorkflowBuilder {

    /** 模型标识，作为选择 key（如 "minimax-h3"） */
    String model();

    /** 参数约束（比例 / 时长 / 图片数等） */
    ModelSpec spec();

    /**
     * 生成 ComfyUI /prompt 所需的工作流图。
     *
     * @param command        生成参数
     * @param imageFilenames 已上传到目标节点的参考图文件名，顺序对应 &lt;Picture 1..N&gt;
     */
    JsonNode build(GenerateCommand command, List<String> imageFilenames) throws Exception;
}
