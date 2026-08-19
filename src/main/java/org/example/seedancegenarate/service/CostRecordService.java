package org.example.seedancegenarate.service;

import com.baomidou.mybatisplus.extension.service.IService;
import org.example.seedancegenarate.entity.CostRecord;
import org.example.seedancegenarate.entity.VideoTask;

public interface CostRecordService extends IService<CostRecord> {
    /**
     * 成功时统一落用户消费记录，金额使用任务提交时的 freeze_amount 快照；幂等。
     */
    void recordOnSuccess(VideoTask task);
}
