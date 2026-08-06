package org.example.seedancegenarate.service.Impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.example.seedancegenarate.engine.BillingTiming;
import org.example.seedancegenarate.engine.VideoEngineRegistry;
import org.example.seedancegenarate.entity.AppUser;
import org.example.seedancegenarate.entity.CostRecord;
import org.example.seedancegenarate.entity.VideoTask;
import org.example.seedancegenarate.mapper.CostRecordMapper;
import org.example.seedancegenarate.service.AppUserService;
import org.example.seedancegenarate.service.CostRecordService;
import org.example.seedancegenarate.service.PricingService;
import org.example.seedancegenarate.service.VideoTaskService;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Slf4j
@Service
public class CostRecordServiceImpl extends ServiceImpl<CostRecordMapper, CostRecord> implements CostRecordService {
    private final AppUserService appUserService;
    private final VideoTaskService videoTaskService;
    private final PricingService pricingService;
    private final VideoEngineRegistry videoEngineRegistry;

    public CostRecordServiceImpl(AppUserService appUserService, @Lazy VideoTaskService videoTaskService,
                                 PricingService pricingService, VideoEngineRegistry videoEngineRegistry) {
        this.appUserService = appUserService;
        this.videoTaskService = videoTaskService;
        this.pricingService = pricingService;
        this.videoEngineRegistry = videoEngineRegistry;
    }

    @Override
    @Transactional
    public void recordOnSubmit(VideoTask task) {
        if (billingTiming(task) == BillingTiming.ON_SUBMIT) {
            doRecord(task);
        }
    }

    @Override
    @Transactional
    public void recordOnSuccess(VideoTask task) {
        if (billingTiming(task) == BillingTiming.ON_SUCCESS) {
            doRecord(task);
        }
    }

    /** 解析该任务提供方的计费时机；provider 缺失（历史数据）或未知时按提交即计费处理 */
    private BillingTiming billingTiming(VideoTask task) {
        String provider = task == null ? null : task.getProvider();
        if (provider == null || provider.isBlank()) {
            return BillingTiming.ON_SUBMIT;
        }
        try {
            return videoEngineRegistry.get(provider).billingTiming();
        } catch (RuntimeException e) {
            log.warn("未知提供方 {}，计费按 ON_SUBMIT 处理", provider);
            return BillingTiming.ON_SUBMIT;
        }
    }

    /** 真正落账：幂等（同一任务已计费则跳过），写 cost_record + 回写 task.costAmount + 累加用户消费 */
    private void doRecord(VideoTask task) {
        if (task == null || task.getId() == null || task.getUserId() == null) {
            return;
        }
        boolean already = this.count(
                Wrappers.<CostRecord>lambdaQuery().eq(CostRecord::getTaskId, task.getId())
        ) > 0;
        if (already) {
            return;
        }

        PricingService.Price price = pricingService.price(task);
        BigDecimal amount = price.amount();

        boolean hasReferenceImage = hasReferenceImage(task);
        CostRecord record = new CostRecord();
        record.setUserId(task.getUserId());
        record.setTaskId(task.getId());
        record.setSeedanceTaskId(task.getTaskId());
        record.setProvider(task.getProvider());
        record.setDuration(task.getDuration());
        record.setUnitPrice(price.unitPrice());
        record.setAmount(amount);
        record.setCurrency(price.currency());
        record.setBizType(hasReferenceImage ? "IMAGE_TO_VIDEO" : "TEXT_TO_VIDEO");
        record.setRemark(providerLabel(task.getProvider()) + (hasReferenceImage ? " 图生视频生成费用" : " 文生视频生成费用"));
        this.save(record);

        VideoTask updateTask = new VideoTask();
        updateTask.setId(task.getId());
        updateTask.setCostAmount(amount);
        videoTaskService.updateById(updateTask);
        task.setCostAmount(amount);

        AppUser user = appUserService.getById(task.getUserId());
        if (user != null) {
            BigDecimal totalCost = user.getTotalCost() == null ? BigDecimal.ZERO : user.getTotalCost();
            AppUser updateUser = new AppUser();
            updateUser.setId(user.getId());
            updateUser.setTotalCost(totalCost.add(amount).setScale(2, RoundingMode.HALF_UP));
            appUserService.updateById(updateUser);
        }
    }

    private String providerLabel(String provider) {
        if (provider == null || provider.isBlank()) {
            return "Seedance";
        }
        return switch (provider) {
            case "seedance" -> "Seedance";
            case "comfyui" -> "ComfyUI";
            default -> provider;
        };
    }

    /**
     * 是否包含参考图：有参考图为图生视频，否则为文生视频
     */
    private boolean hasReferenceImage(VideoTask task) {
        String images = task.getImages();
        if (images == null) {
            return false;
        }
        String trimmed = images.trim();
        return !trimmed.isEmpty() && !"[]".equals(trimmed) && !"null".equals(trimmed);
    }
}
