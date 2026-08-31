package org.example.seedancegenarate.dto;

import lombok.Data;

/**
 * 给自己的某把 key 分配「同时可跑任务数」。
 * <p>
 * <b>单独一个 DTO，不跟改备注共用。</b> 共用的话就有「只想改备注、结果把份额清空了」
 * 这种局部更新陷阱 —— 而这两个字段的 null 都是有意义的值（= 清空），分不出「不改」。
 * <p>
 * 这个字段<b>能安全下放给用户</b>，唯一的理由是它只能收紧：
 * 生效值恒为 {@code min(账号总量, 本值)}（D-032）。用户改它改不出更多容量。
 */
@Data
public class ApiKeyShareRequest {
    /** 分配给这把 key 的同时可跑任务数；null = 不单独分配，与其他 key 共用账号总量 */
    private Integer maxConcurrency;
}
