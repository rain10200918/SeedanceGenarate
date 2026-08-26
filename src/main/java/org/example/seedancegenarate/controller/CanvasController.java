package org.example.seedancegenarate.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.example.seedancegenarate.canvas.CanvasNodeType;
import org.example.seedancegenarate.canvas.CanvasNodeTypeRegistry;
import org.example.seedancegenarate.canvas.InputPort;
import org.example.seedancegenarate.canvas.PortSpec;
import org.example.seedancegenarate.context.UserContext;
import org.example.seedancegenarate.entity.Canvas;
import org.example.seedancegenarate.entity.CanvasNode;
import org.example.seedancegenarate.entity.Result;
import org.example.seedancegenarate.exception.BusinessException;
import org.example.seedancegenarate.service.CanvasRunService;
import org.example.seedancegenarate.service.CanvasService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 无限画布：独立于分镜流水线的一等功能。
 * <p>
 * 节点类型不在本类里枚举 —— 由 {@link CanvasNodeTypeRegistry} 提供，新增一个
 * {@code CanvasNodeType} 实现即自动出现在 {@code /node-types} 里，本类与前端都零改动。
 */
@RestController
@RequestMapping("/api/canvas")
@RequiredArgsConstructor
public class CanvasController {

    /** 只做读解析，ObjectMapper 线程安全 */
    private static final ObjectMapper JSON = new ObjectMapper();

    private final CanvasNodeTypeRegistry nodeTypeRegistry;
    private final CanvasService canvasService;
    private final CanvasRunService canvasRunService;

    @GetMapping
    public Result<List<Canvas>> list() {
        return Result.success(canvasService.listCanvases(UserContext.requireUserId()));
    }

    @PostMapping
    public Result<Canvas> create(@RequestBody(required = false) CreateRequest req) {
        String title = req == null ? null : req.title();
        return Result.success(canvasService.createCanvas(UserContext.requireUserId(), title));
    }

    /** 详情：画布 + 节点（含服务端推导出的端口形状）+ 连线 */
    @GetMapping("/{id}")
    public Result<CanvasService.CanvasDetail> detail(@PathVariable Long id) {
        return Result.success(canvasService.getDetail(UserContext.requireUserId(), id));
    }

    @PutMapping("/{id}")
    public Result<Void> rename(@PathVariable Long id, @RequestBody CreateRequest req) {
        canvasService.renameCanvas(UserContext.requireUserId(), id, req.title());
        return Result.success(null);
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        canvasService.deleteCanvas(UserContext.requireUserId(), id);
        return Result.success(null);
    }

    /**
     * 增量保存：按 key upsert / delete，不整图覆盖。
     * 冲突返回 409（画布已被其他窗口修改），重放返回首次结果。
     */
    @PatchMapping("/{id}")
    public Result<CanvasService.SaveAck> save(@PathVariable Long id,
                                             @RequestBody CanvasService.CanvasMutation mutation) {
        return Result.success(canvasService.applyMutation(UserContext.requireUserId(), id, mutation));
    }

    /**
     * 运行画布：只入队<b>当前就绪</b>的节点（上游都已产出结果的）。
     * 上游未就绪的下游标记 BLOCKED 等待推进，不冻结任何额度。
     */
    @PostMapping("/{id}/run")
    public Result<List<CanvasNode>> run(@PathVariable Long id) {
        return Result.success(canvasRunService.run(UserContext.requireUserId(), id));
    }

    /** 运行/重试单个节点；未就绪则明确报错（显式动作不静默跳过） */
    @PostMapping("/{id}/nodes/{nodeKey}/run")
    public Result<CanvasNode> runNode(@PathVariable Long id, @PathVariable String nodeKey) {
        return Result.success(canvasRunService.runNode(UserContext.requireUserId(), id, nodeKey));
    }

    /**
     * 可用节点类型及其默认端口形状，驱动前端节点面板。
     * <p>
     * 生成节点的端口随所选模型变化，这里给的是「未配置时」的形状；节点配好模型后由详情接口
     * 返回实际端口（服务端推导，前端不复刻规则）。
     */
    @GetMapping("/node-types")
    public Result<List<NodeTypeView>> nodeTypes() {
        UserContext.requireUserId();
        return Result.success(nodeTypeRegistry.all().stream().map(CanvasController::toView).toList());
    }

    /**
     * 端口预览：给定「节点类型 + 配置」返回端口形状。
     * <p>
     * 前端换模型后立即调它刷新连接点，而不是自己复刻一份「imageMax 决定图片口」的推导规则 ——
     * 口径只有服务端一份，永远不会漂移（D-011 同一思路）。
     */
    @PostMapping("/ports")
    public Result<PortsView> ports(@RequestBody PortsRequest req) {
        UserContext.requireUserId();
        CanvasNodeType type = nodeTypeRegistry.get(req.nodeType());
        JsonNode config = null;
        if (req.config() != null && !req.config().isBlank()) {
            try {
                config = JSON.readTree(req.config());
            } catch (Exception e) {
                throw BusinessException.badRequest("节点配置不是合法 JSON");
            }
        }
        PortSpec spec = type.ports(config);
        return Result.success(new PortsView(
                spec.output() == null ? null : spec.output().name(),
                spec.inputs().stream().map(CanvasController::toPortView).toList()));
    }

    private static NodeTypeView toView(CanvasNodeType type) {
        PortSpec spec = type.ports(null);
        return new NodeTypeView(
                type.type(),
                type.label(),
                type.description(),
                type.executable(),
                spec.output() == null ? null : spec.output().name(),
                spec.inputs().stream().map(CanvasController::toPortView).toList());
    }

    private static PortView toPortView(InputPort port) {
        return new PortView(port.id(), port.label(), port.accepts().name(), port.required(), port.max());
    }

    public record CreateRequest(String title) {
    }

    /** 节点类型描述（前端面板据此渲染，不在前端写死类型清单） */
    public record NodeTypeView(String type, String label, String description, boolean executable,
                               String output, List<PortView> inputs) {
    }

    public record PortView(String id, String label, String accepts, boolean required, int max) {
    }

    public record PortsRequest(String nodeType, String config) {
    }

    public record PortsView(String output, List<PortView> inputs) {
    }
}
