package org.example.seedancegenarate.engine;

/**
 * 归一化后的任务状态，屏蔽各提供方的私有返回格式。
 */
public enum GenerationState {
    PROCESSING,
    SUCCESS,
    FAILED,
    /**
     * 远端已经查不到这个作业：既不在队列里，也没有产出。
     * <p>
     * 与 FAILED 的区别是<b>没有结论</b>——作业不是跑失败了，是根本不存在了
     * （ComfyUI 队列是内存态，进程重启即清空）。所以处置不是判用户失败，而是重投。
     */
    LOST
}
