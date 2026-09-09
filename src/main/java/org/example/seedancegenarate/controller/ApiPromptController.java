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

@Slf4j
@RestController
@RequestMapping("/api/v1/prompts")
@RequiredArgsConstructor
public class ApiPromptController {
    private static final int MAX_PROMPT_LENGTH = 5000;
    private static final int MAX_REFERENCE_COUNT = 20;

    private final PromptOptimizeService promptOptimizeService;
    private final ApiVideoService apiVideoService;

    @PostMapping("/optimize")
    public ApiPromptOptimizeResponse optimize(@RequestBody ApiPromptOptimizeRequest request) {
        Long ownerId = UserContext.requireUserId();
        validate(request);
        String original = request.prompt().trim();
        String model = null;
        if (StringUtils.hasText(request.model())) {
            model = apiVideoService.validateModel(request.model()).model();
        }
        try {
            String optimized = promptOptimizeService.optimize(original,
                    new PromptContext(model, request.imageCount(), request.videoCount(), request.audioCount(),
                            request.duration(), normalizedRatio(request.ratio())));
            return new ApiPromptOptimizeResponse(original, optimized, model);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.warn("API 提示词优化失败: ownerId={}, model={}, err={}", ownerId, model, e.getMessage());
            throw ApiException.promptOptimizeUnavailable();
        }
    }

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

    private void validateCount(String field, Integer count) {
        if (count != null && (count < 0 || count > MAX_REFERENCE_COUNT)) {
            throw ApiException.validation(field + " 必须在 0 到 " + MAX_REFERENCE_COUNT + " 之间");
        }
    }

    private String normalizedRatio(String ratio) {
        return StringUtils.hasText(ratio) ? ratio.trim() : null;
    }
}
