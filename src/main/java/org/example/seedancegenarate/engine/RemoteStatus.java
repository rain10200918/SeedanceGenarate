package org.example.seedancegenarate.engine;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 归一化后的任务状态。各提供方在自己的 poll() 里把私有返回翻译成这个对象，
 * 上层（状态落库 / 下载 / 计费）不再感知任何提供方细节。
 */
@Data
@AllArgsConstructor
public class RemoteStatus {
    private GenerationState state;
    /** 成功时的远端视频地址（尚未下载到本地） */
    private String remoteVideoUrl;
    /** 失败时的原始错误信息 */
    private String errorMsg;

    public static RemoteStatus processing() {
        return new RemoteStatus(GenerationState.PROCESSING, null, null);
    }

    public static RemoteStatus success(String remoteVideoUrl) {
        return new RemoteStatus(GenerationState.SUCCESS, remoteVideoUrl, null);
    }

    public static RemoteStatus failed(String errorMsg) {
        return new RemoteStatus(GenerationState.FAILED, null, errorMsg);
    }
}
