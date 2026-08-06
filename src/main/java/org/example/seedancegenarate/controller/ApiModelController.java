package org.example.seedancegenarate.controller;

import lombok.RequiredArgsConstructor;
import org.example.seedancegenarate.context.UserContext;
import org.example.seedancegenarate.dto.ApiModelView;
import org.example.seedancegenarate.engine.ModelSpec;
import org.example.seedancegenarate.engine.VideoEngine;
import org.example.seedancegenarate.engine.VideoEngineRegistry;
import org.example.seedancegenarate.service.ModelAccessService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;

/**
 * 对外 API 模型发现：GET /api/v1/models（ApiKeyInterceptor 鉴权）。
 * 返回可用的模型清单与能力约束，客户端据此拼提交请求；
 * 开关过滤与 UI /options 同一规则（关闭的不下发；属主是管理员则全部可见带 open 标记）。
 */
@RestController
@RequestMapping("/api/v1/models")
@RequiredArgsConstructor
public class ApiModelController {
    private final VideoEngineRegistry videoEngineRegistry;
    private final ModelAccessService modelAccessService;

    @GetMapping
    public List<ApiModelView> list() {
        boolean admin = UserContext.isAdmin();
        return videoEngineRegistry.all().stream()
                .flatMap(engine -> engine.models().stream()
                        .map(spec -> toView(engine.provider(), spec))
                        .filter(model -> admin || model.open()))
                .sorted(Comparator.comparing(ApiModelView::provider).thenComparing(ApiModelView::model))
                .toList();
    }

    private ApiModelView toView(String provider, ModelSpec spec) {
        // durations 为空表示区间可选，展开成离散值（与 UI toModelOption 同一逻辑）
        List<Integer> durations;
        if (!spec.durations().isEmpty()) {
            durations = spec.durations();
        } else if (spec.durationMax() >= spec.durationMin() && spec.durationMax() > 0) {
            durations = IntStream.rangeClosed(spec.durationMin(), spec.durationMax()).boxed().toList();
        } else {
            durations = List.of();
        }
        return new ApiModelView(
                spec.model(), spec.label(), provider,
                spec.outputType().name(),
                spec.needImages(), spec.imageMin(), spec.imageMax(),
                spec.ratios(), durations, spec.megapixels(),
                modelAccessService.isOpen(spec.model())
        );
    }
}
