package org.example.seedancegenarate.engine.comfyui;

/**
 * 选节点时的一道淘汰条件。每一道<b>都要能说出为什么淘汰</b>。
 *
 * <h3>为什么是链，不是一个大 if</h3>
 * 从前选不出节点只抛一句 {@code "所有 ComfyUI 节点均不可用"} —— 从这句话里查不出任何东西。
 * 拆成链之后，失败信息自动是逐节点的淘汰理由清单：
 * <pre>
 *   gpu-0    未启用（人工关闭）
 *   gpu-1    探测失败 12 次，最后一次: 502
 *   gpu-spark 缺 node type: MinimaxLoader
 *   gpu-5090 显存不足: 需要 53.0 GiB，只有 32.0 GiB
 * </pre>
 * 以后加新维度（按机房就近、按客户隔离）时，既有的四道一行不用改，
 * 而且失败信息自动带上新维度的理由。
 *
 * <h3>hard 与 soft</h3>
 * {@link #hard()} 为 true 表示<b>物理上或人为明确地跑不了</b>（未启用、缺插件、显存不够），
 * 任何情况下都不能忽略。false 表示<b>只是当下看起来不行</b>（探测失败）——
 * 当所有节点都被 soft 条件淘汰光时，调度会丢掉 soft 条件重来一遍，
 * 宁可把活派给一台可能病着的机器（提交失败会 markFailed → 解冻，钱不会错），
 * 也不能让整站提交不了。
 */
public interface NodeFilter {

    /** 淘汰理由；<b>null = 通过</b> */
    String reject(NodeState node, String model);

    /** 硬条件（物理/人为不可能）还是软条件（当下不行，全灭时可放弃） */
    default boolean hard() {
        return true;
    }
}
