package org.example.seedancegenarate.dto;

import lombok.Data;

/**
 * 管理员给某账号设并发额度。
 * <p>
 * 两个字段都允许为 null，且 <b>null 是有意义的值</b>（= 清空该项），不是「不改」——
 * 「部分更新」在这种只有两个字段的场景下只会制造「我明明清空了怎么还在」的困惑。
 * <p>
 * 这个 DTO 只出现在 {@code /api/admin/**} 之下（D-010 自动强制 ADMIN）。
 * 自助端<b>永远不会有</b>对应的接口 —— 档位能自选就是自助提权，
 * 和 D-030 里「key 的属主只能来自 UserContext」是同一条理由。
 */
@Data
public class ConcurrencyLimitRequest {

    /** 档位名；null / 空 = 清空。必须是配置里存在的名字，否则会静默失效（接口会告警） */
    private String accountTier;

    /** 单客户覆盖值，优先于档位；null = 清空，0 = 禁止提交，负数 = 拒绝 */
    private Integer concurrencyOverride;
}
