package org.example.seedancegenarate.dto;

import java.util.List;

/**
 * 某账号的在途并发额度「生效后」的样子。
 * <p>
 * <b>为什么要回生效值而不是回填了什么</b>：{@code ConcurrencyPolicy.resolve} 遇到配置里
 * 不存在的档位名会静默按「不限」处理（方向安全：拼错一个名字不该把客户接入打死）。
 * 但对管理员来说这就是个陷阱 —— 他敲了 ENTERPRISE、页面显示 ENTERPRISE、
 * 客户实际一路没限，直到某天出事才发现。所以这里必须把
 * <b>后端算出来的生效值</b>连同来源和告警一起回给他看。
 *
 * <b>为什么还要回接口速率</b>：席位数只说了「能同时跑几个」，但客户得先<b>提交</b>得进来
 * 才谈得上在途。提交速率乘以任务时长才是真正能撑住的在途数 —— 两者不匹配时，
 * 席位数就是个装饰品（设 50 实际只到 25），而这件事在页面上一个字都看不出来。
 *
 * @param accountTier          档位名（原样）
 * @param concurrencyOverride  单客户覆盖值（原样）
 * @param effectiveLimit       生效上限；<b>null = 不限</b>，0 = 禁止提交
 * @param source               生效来源：OVERRIDE / TIER / UNLIMITED
 * @param warning              有陷阱时的说明，正常为 null
 * @param availableTiers       配置里现有的档位名，给前端做下拉用（杜绝手敲拼错）
 * @param apiRatePerMinute     这个账号的接口每分钟能提交几次；null = 不限额账号，不涉及
 * @param reachableInFlight    按上面那个速率算，实际撑得住的在途数；null 同上
 */
public record ConcurrencyLimitView(
        String accountTier,
        Integer concurrencyOverride,
        Integer effectiveLimit,
        String source,
        String warning,
        List<String> availableTiers,
        Integer apiRatePerMinute,
        Integer reachableInFlight
) {
}
