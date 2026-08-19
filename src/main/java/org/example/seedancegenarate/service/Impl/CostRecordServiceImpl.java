package org.example.seedancegenarate.service.Impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.example.seedancegenarate.entity.CostRecord;
import org.example.seedancegenarate.entity.VideoTask;
import org.example.seedancegenarate.mapper.AppUserMapper;
import org.example.seedancegenarate.mapper.CostRecordMapper;
import org.example.seedancegenarate.service.CostRecordService;
import org.example.seedancegenarate.service.PricingService;
import org.example.seedancegenarate.service.VideoTaskService;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Slf4j
@Service
public class CostRecordServiceImpl extends ServiceImpl<CostRecordMapper, CostRecord> implements CostRecordService {
    private final AppUserMapper appUserMapper;
    private final VideoTaskService videoTaskService;
    private final PricingService pricingService;

    public CostRecordServiceImpl(AppUserMapper appUserMapper, @Lazy VideoTaskService videoTaskService,
                                 PricingService pricingService) {
        this.appUserMapper = appUserMapper;
        this.videoTaskService = videoTaskService;
        this.pricingService = pricingService;
    }

    @Override
    @Transactional
    public void recordOnSuccess(VideoTask task) {
        doRecord(task);
    }

    /** 真正落账：幂等（唯一键兜底），写 cost_record + 回写 task.costAmount + 原子累加用户消费 */
    private void doRecord(VideoTask task) {
        if (task == null || task.getId() == null || task.getUserId() == null) {
            return;
        }

        // 用户账务使用提交时快照；旧任务没有快照时才回退到当前价格（仅兼容历史数据）。
        PricingService.Price currentPrice = pricingService.price(task);
        BigDecimal amount = task.getFreezeAmount() != null ? task.getFreezeAmount() : currentPrice.amount();
        BigDecimal unitPrice = task.getFreezeUnitPrice() != null
                ? task.getFreezeUnitPrice() : currentPrice.unitPrice();
        String currency = task.getFreezeCurrency() == null || task.getFreezeCurrency().isBlank()
                ? currentPrice.currency() : task.getFreezeCurrency();

        boolean hasReferenceImage = hasReferenceImage(task);
        CostRecord record = new CostRecord();
        record.setUserId(task.getUserId());
        record.setTaskId(task.getId());
        record.setSeedanceTaskId(task.remoteTaskId());
        record.setProvider(task.getProvider());
        record.setDuration(task.getDuration());
        record.setUnitPrice(unitPrice);
        record.setAmount(amount);
        record.setCurrency(currency);
        record.setBizType(hasReferenceImage ? "IMAGE_TO_VIDEO" : "TEXT_TO_VIDEO");
        record.setRemark(providerLabel(task.getProvider()) + (hasReferenceImage ? " 图生视频生成费用" : " 文生视频生成费用"));
        try {
            this.save(record);
        } catch (DuplicateKeyException e) {
            // 多实例/重复终态并发下，由 uk_cost_record_task_id 保证同一任务只计费一次。
            return;
        }

        VideoTask updateTask = new VideoTask();
        updateTask.setId(task.getId());
        updateTask.setCostAmount(amount);
        videoTaskService.updateById(updateTask);
        task.setCostAmount(amount);

        appUserMapper.incrementTotalCost(task.getUserId(), amount);
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
