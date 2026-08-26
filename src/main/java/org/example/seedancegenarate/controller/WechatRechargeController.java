package org.example.seedancegenarate.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.example.seedancegenarate.context.UserContext;
import org.example.seedancegenarate.entity.RechargeOrder;
import org.example.seedancegenarate.entity.Result;
import org.example.seedancegenarate.service.RechargeChannelAdapter.RechargeCommand;
import org.example.seedancegenarate.service.RechargeChannelRegistry;
import org.example.seedancegenarate.service.WechatPaymentService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

/**
 * 微信充值下单：创建 PENDING 订单并换取 Native 扫码支付链接或 H5 链接。
 * 资金入账由微信异步回调 {@link WechatNotifyController} 验签解密后入账。
 */
@RestController
@RequestMapping("/api/recharge/wechat")
@RequiredArgsConstructor
public class WechatRechargeController {

    private static final BigDecimal MAX_AMOUNT = new BigDecimal("50000");

    private final RechargeChannelRegistry registry;
    private final WechatPaymentService wechatPaymentService;

    @PostMapping("/create")
    public Result<CreateResponse> create(@RequestBody CreateRequest request, HttpServletRequest httpRequest) {
        Long userId = UserContext.requireUserId();
        BigDecimal amount = request.amount();
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new RuntimeException("充值金额必须大于 0");
        }
        if (amount.scale() > 2) {
            throw new RuntimeException("充值金额最多两位小数");
        }
        if (amount.compareTo(MAX_AMOUNT) > 0) {
            throw new RuntimeException("单笔充值金额不能超过 " + MAX_AMOUNT);
        }

        String orderNo = registry.get(RechargeOrder.CHANNEL_WECHAT)
                .createPendingOrder(new RechargeCommand(
                        userId, amount, null, null, "微信充值", request.requestId()));

        String method = request.payMethod() == null || request.payMethod().isBlank()
                ? "native" : request.payMethod().trim();
        String description = "Ascent-api账户余额充值";

        if ("h5".equalsIgnoreCase(method)) {
            String clientIp = getClientIp(httpRequest);
            String h5Url = wechatPaymentService.createH5PayUrl(orderNo, amount, description, clientIp);
            return Result.success(new CreateResponse(orderNo, amount, null, h5Url));
        }

        // 默认 Native 扫码支付 (返回 code_url，前端生成二维码展示)
        String qrCode = wechatPaymentService.createNativePayQrCode(orderNo, amount, description);
        return Result.success(new CreateResponse(orderNo, amount, qrCode, null));
    }

    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isBlank() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isBlank() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip != null ? ip : "127.0.0.1";
    }

    /** payMethod: native=扫码支付(默认) / h5=手机网页支付 */
    public record CreateRequest(BigDecimal amount, String requestId, String payMethod) {}

    public record CreateResponse(String orderNo, BigDecimal amount, String qrCode, String h5Url) {}
}
