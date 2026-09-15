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

/**
 * 【对外生成能力的无副作用辅助接口】
 * <p>
 * 业务定位：
 * 面向外部开发者提供“预询价、试算”等辅助能力。
 * 特点是“无副作用”（Idempotent / No side-effect）：调用此接口仅仅是做价格估算，
 * 不会真正向底层 AI 引擎提交任务，也不会扣减用户钱包里的余额。
 * <p>
 * 访问前缀：/api/v1/generations
 */
@RestController
@RequestMapping("/api/v1/generations")
@RequiredArgsConstructor
public class ApiGenerationController {

    /** 对外 API 生成门面服务：封装了对价格估算、参数归一化、任务提交等编排逻辑 */
    private final ApiVideoService apiVideoService;

    /**
     * 生成任务价格试算 / 询价接口
     * <p>
     * 接口路径：POST /api/v1/generations/quote
     * 场景：开发者在发起真正的生成请求前，可以先调用本接口根据模型名称和时长，预估本次生成需要花费多少金额。
     *
     * @param request 包含请求估算的模型标识（model）以及生成时长（duration）
     * @return ApiGenerationQuoteResponse 包含：提供方 (provider)、实际生效模型 (model)、时长、输出类型 (VIDEO/IMAGE)、单价、预估总额、币种
     */
    @PostMapping("/quote")
    public ApiGenerationQuoteResponse quote(@RequestBody ApiGenerationQuoteRequest request) {
        // 1. 基本参数校验：请求体对象不能为空
        if (request == null) {
            throw ApiException.validation("请求体不能为空");
        }
        // 2. 调用服务层做价格试算（内部会核实模型是否开放、定价规则是按秒还是按次）
        VideoSubmitService.PriceEstimate quote = request.resolution() == null && request.megapixels() == null
                ? apiVideoService.quote(request.model(), request.duration())
                : apiVideoService.quote(request.model(), request.duration(), request.resolution(), request.megapixels());

        // 3. 将试算结果包装为标准对外 API 响应 DTO 返回
        return new ApiGenerationQuoteResponse(
                quote.provider(), quote.model(), quote.duration(), quote.outputType(),
                quote.unitPrice(), quote.amount(), quote.currency(), quote.resolution(), quote.megapixels());
    }
}

