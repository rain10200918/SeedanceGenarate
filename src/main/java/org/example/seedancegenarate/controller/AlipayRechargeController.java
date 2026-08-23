package org.example.seedancegenarate.controller;

import lombok.RequiredArgsConstructor;
import org.example.seedancegenarate.context.UserContext;
import org.example.seedancegenarate.entity.RechargeOrder;
import org.example.seedancegenarate.entity.Result;
import org.example.seedancegenarate.service.AlipayPaymentService;
import org.example.seedancegenarate.service.RechargeChannelAdapter.RechargeCommand;
import org.example.seedancegenarate.service.RechargeChannelRegistry;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

/**
 * 支付宝充值下单：创建 PENDING 订单并换取支付链接。
 * 入账不在这里发生 —— 用户付完由支付宝回调 {@link AlipayNotifyController} 验签后入账。
 * userId 取自 UserContext，不信任前端传入。
 */
@RestController
@RequestMapping("/api/recharge/alipay")
@RequiredArgsConstructor
public class AlipayRechargeController {

    /** 单笔充值上限，挡住误传的天文数字金额 */
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("50000");

    private final RechargeChannelRegistry registry;
    private final AlipayPaymentService alipayPaymentService;

    /** 创建订单 + 按支付方式返回跳转表单或二维码。requestId 可选，用于重复提交幂等 */
    @PostMapping("/create")
    public Result<CreateResponse> create(@RequestBody CreateRequest request) {
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

        String orderNo = registry.get(RechargeOrder.CHANNEL_ALIPAY)
                .createPendingOrder(new RechargeCommand(
                        userId, amount, null, null, "支付宝充值", request.requestId()));

        String method = request.payMethod() == null || request.payMethod().isBlank()
                ? "page" : request.payMethod().trim();
        String subject = "Ascent-api账户余额充值";
        return switch (method) {
            case "wap" -> { // H5 手机网站支付（需签约）
                String payForm = alipayPaymentService.createWapPayUrl(orderNo, amount, subject);
                yield Result.success(new CreateResponse(orderNo, amount, payForm, null));
            }
            case "precreate" -> { // 当面付：返回二维码内容，用户用支付宝 App 扫码
                String qrCode = alipayPaymentService.createQrCode(orderNo, amount, subject);
                yield Result.success(new CreateResponse(orderNo, amount, null, qrCode));
            }
            case "defalut" ->{
                String computerPay = alipayPaymentService.createPagePayUrl(orderNo, amount, subject);
                yield Result.success(new CreateResponse(orderNo, amount, computerPay, null));
            }
            default -> { // 电脑网站支付（已签约，默认）
                String payForm = alipayPaymentService.createPagePayUrl(orderNo, amount, subject);
                yield Result.success(new CreateResponse(orderNo, amount, payForm, null));
            }
        };
    }

    /** payMethod: page=电脑网站支付(默认) / wap=手机网站支付 / precreate=当面付扫码 */
    record CreateRequest(BigDecimal amount, String requestId, String payMethod) {}

    /** payForm 与 qrCode 二选一：页面跳转（page/wap）返回表单，当面付（precreate）返回二维码内容 */
    record CreateResponse(String orderNo, BigDecimal amount, String payForm, String qrCode) {}
}
