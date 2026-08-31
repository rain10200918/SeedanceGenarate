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
     * 模板在 classpath 上的位置，如 {@code comfyui/workflows/z-image-turbo.json}。
     * <p>
     * {@link WorkflowRequirements} 启动时解析它，得出「这个工作流需要哪些 node type」——
     * 有了这份清单，调度才能在 pick 阶段就排除掉装不全插件的节点，
     * 而不是提交过去等它报 {@code missing_node_type}。
     * <p>
     * <b>为什么不按 {@code model()} 推路径</b>：10 个模型确实和文件同名，但
     * {@code minimax-h3} 对应的是 {@code minimax-h3-ref2v.json}。
     * 靠约定的话，下一个命名不一致的 builder 会静默地失去能力过滤（降级安全但没人知道），
     * 写成接口方法则是编译器强制申报。
     */
    String templatePath();

    /**
     * 生成 ComfyUI /prompt 所需的工作流图。
     *
     * @param command 生成参数
     * @param files   已上传到目标节点的参考素材文件名（图片 / 视频 / 音频，顺序对应 &lt;Picture 1..N&gt; 等）
     */
    JsonNode build(GenerateCommand command, ReferenceFiles files) throws Exception;
}
