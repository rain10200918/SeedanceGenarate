package org.example.seedancegenarate.service;

import com.baomidou.mybatisplus.extension.service.IService;
import org.example.seedancegenarate.entity.CostRecord;
import org.example.seedancegenarate.entity.VideoTask;

public interface CostRecordService extends IService<CostRecord> {
    /**
     * 提交时计费。仅对「提交即计费」的提供方（如 Seedance）生效；「成功才计费」的提供方在此为空操作。
     */
    void recordOnSubmit(VideoTask task);

    /**
     * 成功时计费。仅对「成功才计费」的提供方（如 ComfyUI）生效；其余提供方在此为空操作。幂等。
     */
    void recordOnSuccess(VideoTask task);
}
