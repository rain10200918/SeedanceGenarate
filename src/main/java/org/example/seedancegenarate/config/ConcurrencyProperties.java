package org.example.seedancegenarate.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 在途并发额度配置。
 * <p>
 * 档位<b>名字</b>存在 {@code app_user.account_tier}，<b>数值</b>在这里 —— 调整「企业版 = 50 路」
 * 是改 yaml 重启，不是 UPDATE 一万行，而且能灰度、能回滚。
 * <p>
 * 老化窗口<b>刻意不给独立默认值</b>：见 {@code ConcurrencyPolicy#agingWindowSeconds()}，
 * 它从任务超时和重试次数推导。写死一个数的话，将来谁把 {@code video.task-timeout-minutes}
 * 调大，这道保险就会静默失效。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "concurrency")
public class ConcurrencyProperties {

    /** 总开关。关掉 = 提交路径一次 Redis 都不碰，行为与改动前逐字相同 */
    private boolean enabled = true;

    /**
     * 影子模式：照常记账、照常打日志，<b>但不拒绝任何请求</b>。
     * <p>
     * 默认 false。这条链路只对<b>被管理员显式设过上限的账号</b>生效 ——
     * 没设的账号 {@code unlimited()} 直接 return，一次 Redis 都不碰。
     * 所以「真的拒绝」的影响面恰好等于人手工配过的那几个账号，不存在全量静默降级，
     * 也就不需要拿影子模式换观察期。真要观察时把它打开，日志前缀会变成 {@code [影子]}。
     */
    private boolean shadow = false;

    /** 档位 → 在途上限。库里的 account_tier 在这里查不到时按「不限」处理 */
    private Map<String, Integer> tiers = new LinkedHashMap<>();

    /**
     * 老化窗口的安全系数：推导值 = 任务最长合法寿命 × 本系数。
     * <p>
     * 老化是所有机制都失灵时的最后一道。窗口小于任务合法寿命时，长任务会在<b>还在跑的时候</b>
     * 被淘汰、槽位提前释放 —— 超发，而且恰好发生在系统最忙的时候。
     */
    private int agingSafetyFactor = 2;

    /**
     * 运维应急用的老化窗口硬覆盖（秒）。null = 用推导值。
     * <p>
     * 配了但小于推导值时<b>启动直接失败</b>，不允许「配错了等运行时才发现」。
     */
    private Long agingWindowSecondsOverride;

    /** Redis key 前缀；生产、预发、本地共用 Redis 时必须不同，否则互相吃对方的额度 */
    private String redisKeyPrefix = "local:seedance:conc";

    /**
     * 任务平均耗时（分钟）—— 把「席位数」换算成「接口每分钟要放几次」的唯一系数。
     * <p>
     * 要维持 N 个在途，提交速率就得达到 {@code N ÷ 本值} 次/分钟。实测加权平均 ≈4.7
     * （视频 ≈6min、图 ≈31s），取 5。
     * <p>
     * 这个数<b>偏大比偏小安全</b>：偏大 → 推出的桶偏紧 → 客户跑不满席位（看得见，会来问）；
     * 偏小 → 桶偏松 → 并发额度仍然兜着，只是重试风暴时多打几次数据库。
     */
    private int avgTaskMinutes = 5;

    /**
     * 有席位的账号，API 令牌桶按席位自动放宽（{@code max(默认值, 推导值)}，只放宽不收紧）。
     * <p>
     * 并发额度存在之后令牌桶的职责就变了：对有席位的账号，并发额度是更好的防线
     * （自计时、且正是卖出去的东西），令牌桶再卡着就是在跟自己收的钱打架。
     * <p>
     * 关掉 = 所有账号共用默认桶，回到这一刀之前的行为；此时席位数一旦超过桶能撑住的量，
     * 管理端弹窗会出黄字告警 —— 关掉可以，但不许悄悄地关。
     */
    private boolean deriveApiRateLimit = true;
}
