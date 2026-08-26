package org.example.seedancegenarate.canvas;

import com.fasterxml.jackson.databind.JsonNode;
import org.example.seedancegenarate.entity.CanvasNode;

/**
 * 一种画布节点类型。**扩展点：新增一个 {@code @Component} 实现即多一种节点** ——
 * {@link CanvasNodeTypeRegistry} 自动收录，保存校验、端口推导、前端节点面板都随之生效，
 * CanvasService / CanvasController / 前端都不用改。
 * <p>
 * 与 {@code VideoEngine} 是同一套路：接口 + Spring 注入全部实现 + 注册表按 key 索引。
 */
public interface CanvasNodeType {

    /** 注册表 key，同时是 canvas_node.node_type 的值 */
    String type();

    /** 前端节点面板展示名 */
    String label();

    /** 一句话说明，前端面板副标题 */
    default String description() {
        return "";
    }

    /**
     * 按当前配置推导端口形状。config 为 null 视为「刚拖出来、还没配」。
     * 实现里不要抛异常——配置不完整时给出退化的端口形状即可（校验交给 validateConfig）。
     */
    PortSpec ports(JsonNode config);

    /** 保存时校验配置；不合法抛 BusinessException。默认不校验。 */
    default void validateConfig(JsonNode config) {
    }

    /** 是否需要执行（产生任务并计费）。素材/文本节点为 false。 */
    default boolean executable() {
        return false;
    }

    /**
     * 运行前的就绪自检：必填端口是否都接上了。返回 null = 就绪，否则返回不能运行的原因。
     * 默认按 {@link PortSpec} 的 required 端口检查。
     */
    default String readinessError(CanvasNode node, JsonNode config, ResolvedInputs inputs) {
        for (InputPort port : ports(config).inputs()) {
            if (port.required() && inputs.of(port.id()).isEmpty()) {
                return "缺少必填输入：" + port.label();
            }
        }
        return null;
    }

    /**
     * 组装提交计划：把节点配置和各端口上解析出的上游产物翻译成一次生成请求。
     * 仅 {@link #executable()} 为 true 的类型需要实现。
     */
    default SubmitPlan plan(CanvasNode node, JsonNode config, ResolvedInputs inputs) {
        throw new UnsupportedOperationException(type() + " 类型的节点不需要执行");
    }

    /**
     * 该节点当前能向下游提供什么产物。null = 暂无产物（如生成节点还没跑完）。
     * 源节点（素材/文本）直接从配置取，生成节点从 {@code output} 取。
     */
    default ResolvedInputs.PortValue output(CanvasNode node, JsonNode config) {
        return null;
    }
}
