package org.example.seedancegenarate.service;

import com.baomidou.mybatisplus.extension.service.IService;
import org.example.seedancegenarate.engine.RemoteStatus;
import org.example.seedancegenarate.entity.VideoTask;

public interface VideoTaskService extends IService<VideoTask> {
    /**
     * 依据引擎归一化后的 {@link RemoteStatus} 落库（与提供方无关）：
     * 成功则把远端视频下载到本地并计费（成功计费的提供方），失败则记录错误信息。
     * 会就地更新传入的 {@code task}，供上层直接返回。
     */
    void updateStatus(VideoTask task, RemoteStatus status) throws Exception;
}
