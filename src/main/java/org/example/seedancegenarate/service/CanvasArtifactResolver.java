package org.example.seedancegenarate.service;

import org.example.seedancegenarate.canvas.ResolvedInputs;
import org.example.seedancegenarate.entity.CanvasNode;

/**
 * 上游节点的产物 → 下游引擎真能下载的地址。
 * <p>
 * 画布之前没有这个环节：分镜流水的每个镜头都从共享素材池取图，节点之间不传产物。
 * 画布把「上一个节点生成的图」接到「下一个节点的参考图口」，第一次需要回答
 * 「一个刚生成出来的东西，怎么变成别人能拉取的地址」。
 * <p>
 * 两种来源的形状完全不同，这就是本接口存在的理由：
 * <ul>
 *   <li>素材节点：{@code user_asset.url} 本来就是公网 https 地址，原样透传</li>
 *   <li>生成节点：落库的是展示用的 key（{@code tsk_xxx.png}），真产物在对象存储的
 *       {@code artifact_key} 下且只允许签名访问 —— 必须在提交那一刻现签一个短期地址</li>
 * </ul>
 * 换不出来一律抛错而不是硬着头皮提交：提交意味着冻结钱，拿着一个引擎下载不了的字符串
 * 去生成，四分钟后只会换来一句「Unexpected end of file from server」。
 */
public interface CanvasArtifactResolver {

    /**
     * @param producer 产出该值的上游节点（需要它的 taskId 才能反查产物）
     * @param value    上游节点自报的产物
     * @return 可下载地址版本的产物；TEXT 与已经是 http(s) 的值原样返回
     * @throws org.example.seedancegenarate.exception.BusinessException 换不出可下载地址
     */
    ResolvedInputs.PortValue toFetchable(CanvasNode producer, ResolvedInputs.PortValue value);
}
