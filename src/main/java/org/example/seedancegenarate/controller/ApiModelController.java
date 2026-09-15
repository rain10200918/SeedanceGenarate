package org.example.seedancegenarate.controller;

import lombok.RequiredArgsConstructor;
import org.example.seedancegenarate.context.UserContext;
import org.example.seedancegenarate.dto.ApiModelView;
import org.example.seedancegenarate.engine.ModelSpec;
import org.example.seedancegenarate.engine.VideoEngineRegistry;
import org.example.seedancegenarate.service.ModelAccessService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;

/**
 * 【对外 API - 模型发现与能力元数据控制器】
 * <p>
 * 业务定位：
 * 面向持 API Key 调用的外部开发者，提供查询当前系统支持的所有 AI 模型及其能力清单（Model Discovery）。
 * 开发者在自己编写调用脚本时，可以通过调用本接口知道：
 * 1. 系统现在有哪些模型可用（如 minimax-h3, z-image-turbo 等）；
 * 2. 这个模型是生成视频（VIDEO）还是生成图片（IMAGE）；
 * 3. 它支持哪些画面比例（16:9, 9:16 等）、哪些时长档位、最多允许传几张参考图。
 * <p>
 * 访问前缀：/api/v1/models
 * 安全说明：由 ApiKeyInterceptor 鉴权拦截。
 */
@RestController
@RequestMapping("/api/v1/models")
@RequiredArgsConstructor
public class ApiModelController {

    /** 引擎注册表：保存了所有接入的 AI 提供方引擎（Seedance, ComfyUI）及其所有模型的规范定义 */
    private final VideoEngineRegistry videoEngineRegistry;

    /** 模型开放状态服务：管理后台对各模型的动态上下线开关 */
    private final ModelAccessService modelAccessService;

    /**
     * 获取当前对外部开放的模型能力清单
     * <p>
     * 接口路径：GET /api/v1/models
     * 业务规则：
     * 1. 过滤规则：已被管理员在后台关闭的模型，对普通开发者不可见（不下发）；
     * 2. 管理员特权：如果调用的 Key 属于管理员，则返回全量模型，并在 open 字段标记其开关状态（便于上线前自测）；
     * 3. 排序规则：按提供方（provider）升序，同提供方内按模型名称（model）升序排列。
     *
     * @return List<ApiModelView>
     */
    @GetMapping
    public List<ApiModelView> list() {
        // 1. 判断当前调用者是否具备管理员身份
        boolean admin = UserContext.isAdmin();

        // 2. 遍历引擎注册表中的所有引擎实例
        return videoEngineRegistry.all().stream()
                // 3. 展开每个引擎所支持的模型规格列表（ModelSpec），转换为外部视图 DTO
                .flatMap(engine -> engine.models().stream()
                        .map(spec -> toView(engine.provider(), spec))
                        // 4. 如果不是管理员，只保留已开放（open=true）的模型
                        .filter(model -> admin || model.open()))
                // 5. 按照 provider 和 model 双重排序，保证下发顺序稳定
                .sorted(Comparator.comparing(ApiModelView::provider).thenComparing(ApiModelView::model))
                .toList();
    }

    /**
     * 将内部引擎的 ModelSpec 模型规范转换为对外统一的 ApiModelView DTO
     *
     * @param provider 提供方标识（如 seedance, comfyui）
     * @param spec 引擎层定义的原始模型约束对象
     * @return ApiModelView
     */
    private ApiModelView toView(String provider, ModelSpec spec) {
        // 计算支持的时长档位：
        // 1. 若配置了离散列表（如 [5, 10]），直接使用；
        // 2. 若 durations 为空且配置了连续区间（如 3 到 6 秒），展开成连续的整数列表 [3, 4, 5, 6]；
        // 3. 否则（如图片模型无需时长）返回空列表。
        List<Integer> durations;
        if (!spec.durations().isEmpty()) {
            durations = spec.durations();
        } else if (spec.durationMax() >= spec.durationMin() && spec.durationMax() > 0) {
            durations = IntStream.rangeClosed(spec.durationMin(), spec.durationMax()).boxed().toList();
        } else {
            durations = List.of();
        }

        // 构造对外视图传输对象并返回
        return new ApiModelView(
                spec.model(), spec.label(), provider,
                spec.outputType().name(),
                spec.needImages(), spec.imageMin(), spec.imageMax(),
                spec.ratios(), durations, spec.megapixels(),
                modelAccessService.isOpen(spec.model()),
                spec.videoMax(), spec.audioMax(), spec.needImageOrVideo(),
                spec.imageInputMode().name(),spec.resolutions(),spec.defaultResolution()
        );
    }
}

