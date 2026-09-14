package org.example.seedancegenarate.service.Impl;

import lombok.RequiredArgsConstructor;
import org.example.seedancegenarate.entity.VideoTask;
import org.example.seedancegenarate.service.PricingService;
import org.example.seedancegenarate.service.WalletService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/** 在任务失败的 outer terminal 事务提交后，用独立短事务完成钱包解冻。 */
@Service
@RequiredArgsConstructor
public class FailureWalletReleaseService {
    private final WalletService walletService;
    private final PricingService pricingService;
    @org.springframework.beans.factory.annotation.Autowired
    private org.example.seedancegenarate.service.BillingAuthorizationService billingAuthorization;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(VideoTask task) {
        BigDecimal releaseAmount = task.getFreezeAmount() != null ? task.getFreezeAmount()
                : pricingService.price(task).amount();
        if (task.getApiKeyId() == null) {
            walletService.release(task.getUserId(), releaseAmount, task.getId());
        } else {
            billingAuthorization.release(task, releaseAmount);
        }
    }
}
