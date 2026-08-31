package org.example.seedancegenarate.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 一台 ComfyUI 算力节点，<b>只存人填的那一半</b>。
 * <p>
 * 死活 / 队列深度 / node type / 显存 / 版本是<b>观测态</b>，活在
 * {@code ComfyUiFleet} 的内存快照里，不落库 —— 3 秒探一轮 × N 台是一天十几万次写，
 * 而且多实例会互相覆盖。
 * <p>
 * 主键是业务 id（{@code gpu-0}）不是自增：{@code video_task.node_id} 存的就是它。
 */
@Data
@TableName("comfy_node")
public class ComfyNode {

    /** 节点 ID，如 gpu-0。建后不可改（改了等于新建一台，旧任务会找不到节点） */
    @TableId(type = IdType.INPUT)
    private String id;

    private String baseUrl;

    /** 派不派活。<b>新增默认 false</b> —— 新机器先用「指定节点提交」验证，再放量 */
    private Boolean enabled;

    /**
     * 列表里显不显示。归档的节点仍然留在快照里（否则它上面的在途任务会查不到节点被判死），
     * 只是探测器不再探它、管理端默认不列它。
     */
    private Boolean archived;

    /** 相对算力，H100 = 1.00 */
    private BigDecimal weight;

    /** 给人看的：这台在哪、谁维护、为什么关着 */
    private String remark;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
