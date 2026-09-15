package org.example.seedancegenarate.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.seedancegenarate.context.UserContext;
import org.example.seedancegenarate.dto.ApiPromptOptimizeRequest;
import org.example.seedancegenarate.dto.ApiPromptOptimizeResponse;
import org.example.seedancegenarate.exception.ApiException;
import org.example.seedancegenarate.service.ApiVideoService;
import org.example.seedancegenarate.service.PromptContext;
import org.example.seedancegenarate.service.PromptOptimizeService;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 【对外 API - 提示词（Prompt）智能优化控制器】
 * <p>
 * 业务定位：
 * 用户手写的提示词往往比较简短或口语化（例如：“一只小狗在跑”），直接丢给视频生成模型可能效果欠佳。
 * 本接口充当【后端大模型（LLM）代理】：将用户的简短 Prompt 结合目标模型特性、素材数量、画面比例，
 * 扩写、润色为具备丰富镜头语言、光影描写、细节充实的专业级 AI 生成提示词。
 * <p>
 * 安全架构收益：
 * 大模型的 API Key 与上游模型端点均由后端安全持有，绝不下发给外部调用方。
 * <p>
 * 访问前缀：/api/v1/prompts
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/prompts")
@RequiredArgsConstructor
public class ApiPromptController {

    /** 提示词最大允许字符数，防止超长文本打爆 LLM 上下文 */
    private static final int MAX_PROMPT_LENGTH = 5000;

    /** 允许传入的最大参考素材数量上限 */
    private static final int MAX_REFERENCE_COUNT = 20;

    /** 提示词优化服务：负责对接大模型代理并按模板填充上下文 */
    private final PromptOptimizeService promptOptimizeService;

    /** 对外视频服务：用于校验传入的目标模型是否合法 */
    private final ApiVideoService apiVideoService;

    /**
     * 智能扩写 / 润色生成提示词
     * <p>
     * 接口路径：POST /api/v1/prompts/optimize
     *
     * @param request 包含原始 prompt、目标模型、参考图片/视频/音频数量、时长、画面比例等
     * @return ApiPromptOptimizeResponse 包含：原始提示词 (originalPrompt)、优化后提示词 (optimizedPrompt)、生效模型 (model)
     */
    @PostMapping("/optimize")
    public ApiPromptOptimizeResponse optimize(@RequestBody ApiPromptOptimizeRequest request) {
        // 1. 提取当前调用者属主 ID
        Long ownerId = UserContext.requireUserId();

        // 2. 参数合法性校验（长度限制、数量范围限制）
        validate(request);

        // 3. 提取并清理原始提示词
        String original = request.prompt().trim();
        String model = null;

        // 4. 如果显式指定了模型，校验模型是否存在且处于开放状态
        if (StringUtils.hasText(request.model())) {
            model = apiVideoService.validateModel(request.model()).model();
        }

        try {
            // 5. 调用大模型代理服务，传入上下文（模型、素材数量、时长、比例），完成专业化扩写
            String optimized = promptOptimizeService.optimize(original,
                    new PromptContext(model, request.imageCount(), request.videoCount(), request.audioCount(),
                            request.duration(), normalizedRatio(request.ratio())));

            // 6. 返回扩写后的新提示词
            return new ApiPromptOptimizeResponse(original, optimized, model);
        } catch (ApiException e) {
            // 业务异常直接往外抛（如模型未开放、参数不合法）
            throw e;
        } catch (Exception e) {
            // 上游大模型超时或报错时记录警告日志，并返回规范的 503 提示词服务暂不可用错误
            log.warn("API 提示词优化失败: ownerId={}, model={}, err={}", ownerId, model, e.getMessage());
            throw ApiException.promptOptimizeUnavailable();
        }
    }

    /**
     * 针对优化请求入参的严格防御性校验
     */
    private void validate(ApiPromptOptimizeRequest request) {
        if (request == null || !StringUtils.hasText(request.prompt())) {
            throw ApiException.validation("prompt 不能为空");
        }
        if (request.prompt().trim().length() > MAX_PROMPT_LENGTH) {
            throw ApiException.validation("prompt 不能超过 " + MAX_PROMPT_LENGTH + " 个字符");
        }
        validateCount("imageCount", request.imageCount());
        validateCount("videoCount", request.videoCount());
        validateCount("audioCount", request.audioCount());
        if (request.duration() != null && (request.duration() < 1 || request.duration() > 600)) {
            throw ApiException.validation("duration 必须在 1 到 600 之间");
        }
        if (request.ratio() != null && request.ratio().trim().length() > 32) {
            throw ApiException.validation("ratio 不能超过 32 个字符");
        }
    }

    /**
     * 校验素材数量不能为负数且不能超过系统上限
     */
    private void validateCount(String field, Integer count) {
        if (count != null && (count < 0 || count > MAX_REFERENCE_COUNT)) {
            throw ApiException.validation(field + " 必须在 0 到 " + MAX_REFERENCE_COUNT + " 之间");
        }
    }

    /**
     * 规范化画面比例字符串（剔除首尾空白）
     */
    private String normalizedRatio(String ratio) {
        return StringUtils.hasText(ratio) ? ratio.trim() : null;
    }
}

