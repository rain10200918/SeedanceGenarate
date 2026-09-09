package org.example.seedancegenarate.controller;

import lombok.RequiredArgsConstructor;
import org.example.seedancegenarate.dto.ApiGenerationQuoteRequest;
import org.example.seedancegenarate.dto.ApiGenerationQuoteResponse;
import org.example.seedancegenarate.exception.ApiException;
import org.example.seedancegenarate.service.ApiVideoService;
import org.example.seedancegenarate.service.VideoSubmitService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 对外生成能力的无副作用辅助接口。 */
@RestController
@RequestMapping("/api/v1/generations")
@RequiredArgsConstructor
public class ApiGenerationController {
    private final ApiVideoService apiVideoService;

    @PostMapping("/quote")
    public ApiGenerationQuoteResponse quote(@RequestBody ApiGenerationQuoteRequest request) {
        if (request == null) {
            throw ApiException.validation("请求体不能为空");
        }
        VideoSubmitService.PriceEstimate quote = apiVideoService.quote(request.model(), request.duration());
        return new ApiGenerationQuoteResponse(
                quote.provider(), quote.model(), quote.duration(), quote.outputType(),
                quote.unitPrice(), quote.amount(), quote.currency());
    }
}
