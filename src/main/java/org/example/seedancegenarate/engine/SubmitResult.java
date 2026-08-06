package org.example.seedancegenarate.engine;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 提交生成任务后的结果。
 */
@Data
@AllArgsConstructor
public class SubmitResult {
    /** 提供方返回的任务 ID（Seedance 的 id / ComfyUI 的 prompt_id） */
    private String providerTaskId;
    /** 处理该任务的节点 ID（ComfyUI 多节点亲和性用；Seedance 为 null） */
    private String nodeId;

    public static SubmitResult of(String providerTaskId) {
        return new SubmitResult(providerTaskId, null);
    }

    public static SubmitResult of(String providerTaskId, String nodeId) {
        return new SubmitResult(providerTaskId, nodeId);
    }
}
