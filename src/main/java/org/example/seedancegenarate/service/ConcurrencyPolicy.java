package org.example.seedancegenarate.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.example.seedancegenarate.config.ConcurrencyProperties;
import org.example.seedancegenarate.config.RateLimitConfig;
import org.example.seedancegenarate.dto.ConcurrencyLimitView;
import org.example.seedancegenarate.entity.ApiKey;
import org.example.seedancegenarate.entity.AppUser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import java.util.ArrayList;
import java.util.List;

/**
 * 「这个账号此刻的在途并发上限是多少」的<b>唯一出口</b>，以及老化窗口的唯一推导处。
 * <p>
 * 三处配置（档位表 / 账号 override / 将来的 key 级上限）只在这里解读一次 ——
 * 同 {@link ArtifactExpiryPolicy} 的思路：口径散开必然漂移，而这条路径上漂移的代价是
 * 「客户以为买了 50 路，实际按 3 路拒他」。
 *
 * <h3>为什么 null 是「不限」而不是「0」</h3>
 * 默认值会给<b>所有存量用户</b>生效。个人用户没有合同、没有预期管理，一个非空默认值
 * 就是一次静默的全量降级，第一个发现的会是客服。所以库里三列全 NULL、
 * 配置里 tiers 默认空表 —— 落地即现状。
 *
 * <h3>这里同时是「席位 → 接口限速」的换算处</h3>
 * 席位数只说了「能同时跑几个」，客户还得先<b>提交</b>得进来 —— 提交速率 × 任务时长
 * 才是真正能撑住的在途数。{@link #apiBucketFor} 和管理端弹窗那句提示
 * <b>必须共用这一份公式</b>：两份必漂，而漂了的症状是「页面说撑得住 50，实际 25」，
 * 没有任何报错。
 */
@Slf4j
@Component
public class ConcurrencyPolicy {

    private final ConcurrencyProperties properties;
    private final RateLimitConfig rateLimitConfig;

    /** 任务超时判定基准；老化窗口从它推导，不许各自写死 */
    @Value("${video.task-timeout-minutes:60}")
    private int taskTimeoutMinutes;

    @Value("${video.timeout-retry-max:2}")
    private int timeoutRetryMax;

    public ConcurrencyPolicy(ConcurrencyProperties properties, RateLimitConfig rateLimitConfig) {
        this.properties = properties;
        this.rateLimitConfig = rateLimitConfig;
    }

    /**
     * 账号的生效上限。
     * <p>
     * 优先级：单客户 override > 档位表 > 不限。
     * 查不到档位（库里写了个配置里没有的名字）一律按<b>不限</b>处理 ——
     * 方向必须是安全的：拼错一个档位名不该把客户的接入打死。
     */
    public ConcurrencyLimit resolve(AppUser user) {
        return resolve(user, null);
    }

    /**
     * 加上「这把 key 分到多少份额」那一层。
     * <p>
     * <b>key 份额只能收紧，不能放宽</b>：取 {@code min(账号总量, key 份额)}。
     * 允许放宽的话，企业自己建 key 就能自助扩容 —— 和 D-030 里「按 key 分桶等于自助绕过限流」
     * 是同一个洞，只是换了个入口。所以这个开关放给用户自己设是安全的。
     * <p>
     * {@code apiKey} 为 null（网页、画布）时只受账号总量管。
     */
    public ConcurrencyLimit resolve(AppUser user, ApiKey apiKey) {
        if (!properties.isEnabled() || user == null) {
            return ConcurrencyLimit.UNLIMITED;
        }
        Integer accountMax = resolveAccountMax(user);
        Integer keyMax = apiKey == null ? null : apiKey.getMaxConcurrency();
        if (keyMax != null && keyMax < 0) {
            keyMax = 0;
        }
        // 只能收紧：key 份额填得比账号总量还大时，按账号总量算
        if (keyMax != null && accountMax != null && keyMax > accountMax) {
            keyMax = accountMax;
        }
        return ConcurrencyLimit.of(accountMax, keyMax);
    }

    /**
     * 账号买到的总量，<b>只看管理员能设的字段</b>（档位 / override）。null = 没买额度。
     *
     * <h3>两档令牌桶的判别必须用这个方法</h3>
     * <b>不许</b>用 {@code resolve(user, key).unlimited()} —— 那个还含
     * {@code api_key.max_concurrency}，而那一列是<b>用户自助能改的</b>
     * （{@code PATCH /api/api-keys/{id}/share}）。用它当判别条件的话，
     * 一个没有席位的个人用户只要给自己的 key 随便设个份额，就把自己送进了松桶，
     * 而账号侧没有任何总量去约束这些份额的和 —— D-030 那个洞的第三次变形。
     */
    public Integer accountMax(AppUser user) {
        if (!properties.isEnabled() || user == null) {
            return null;
        }
        return resolveAccountMax(user);
    }

    /**
     * 有席位的账号该用的 API 令牌桶：<b>按席位推导，且只放宽不收紧</b>。
     *
     * <h3>为什么要推</h3>
     * 席位数只说了「能同时跑几个」，可客户得先提交得进来。默认桶是 5 次/分钟，
     * 任务平均 5 分钟 —— 撑得住的在途数是 25，所以 50 席以上的数字全是装饰品。
     *
     * <h3>为什么 max() 两处都要</h3>
     * 保证这个方法<b>不可能</b>让任何账号比默认更严。少了 max，一个 1 席的账号会被推成
     * capacity=1，比今天的 10 还紧 —— 一刀下去把本来能跑的账号弄坏，
     * 而这类回归最难在测试里想到。有了它，这一刀在最坏情况下等于什么都没做。
     */
    public RateLimitConfig.Bucket apiBucketFor(int seats) {
        RateLimitConfig.Bucket base = rateLimitConfig.getApiKey();
        if (!properties.isDeriveApiRateLimit() || seats <= 0) {
            return base;
        }
        long refillSeconds = base.getRefillSeconds();
        // 每个补充周期要放几个，才够把 seats 个席位持续填满
        int needed = (int) Math.ceil(seats * refillSeconds / (60.0 * properties.getAvgTaskMinutes()));
        return new RateLimitConfig.Bucket(
                base.getEnabled(),
                Math.max(base.getCapacity(), seats),
                Math.max(base.getRefillTokens(), needed),
                base.getRefillSeconds());
    }

    /** 这个桶每分钟放几次 */
    public int ratePerMinute(RateLimitConfig.Bucket bucket) {
        return (int) Math.floor(bucket.getRefillTokens() * 60.0 / bucket.getRefillSeconds());
    }

    /**
     * 按这个桶的补充速率，实际撑得住多少个在途任务。
     * <p>
     * 「提交速率 × 任务时长 = 稳态在途数」—— 席位数超过它就是句空话。
     * {@link #apiBucketFor} 的构造保证了 {@code reachableInFlight(apiBucketFor(n)) >= n}。
     */
    public int reachableInFlight(RateLimitConfig.Bucket bucket) {
        return (int) Math.floor(ratePerMinute(bucket) * (double) properties.getAvgTaskMinutes());
    }

    /** 账号总量：单客户 override > 档位表 > 不限 */
    private Integer resolveAccountMax(AppUser user) {
        Integer override = user.getConcurrencyOverride();
        if (override != null) {
            return Math.max(override, 0);
        }
        String tier = user.getAccountTier();
        if (!StringUtils.hasText(tier)) {
            return null;
        }
        Integer byTier = properties.getTiers().get(tier.trim());
        if (byTier == null) {
            log.warn("账号档位在配置里找不到，按不限处理: userId={}, tier={}", user.getId(), tier);
            return null;
        }
        return Math.max(byTier, 0);
    }

    /**
     * 把「生效后长什么样」讲给管理端听 —— 包括那些会静默失效的情况。
     * <p>
     * 管理端<b>不许自己再推一遍</b> min/优先级/未知档位怎么办：那样就有两份口径，
     * 而这两份一旦漂移，症状是「页面显示限了 50，实际一路没限」。
     */
    public ConcurrencyLimitView describe(AppUser user) {
        List<String> tiers = new ArrayList<>(properties.getTiers().keySet());
        if (user == null) {
            return new ConcurrencyLimitView(null, null, null, "UNLIMITED", null, tiers, null, null);
        }
        String tier = user.getAccountTier();
        Integer override = user.getConcurrencyOverride();
        ConcurrencyLimit limit = resolve(user);
        Integer effective = limit.unlimited() ? null : limit.max();

        // 告警文案写给**运营**看，不是写给工程师看：不出现配置项名、不出现英文枚举。
        // 运营看不懂就会来问人，问到第三次就没人再看这些字了。
        String source;
        String warning = null;
        if (!properties.isEnabled()) {
            source = "UNLIMITED";
            warning = "并发限制功能没有开启，这里设的数字暂时不生效。";
        } else if (override != null) {
            source = "OVERRIDE";
        } else if (StringUtils.hasText(tier)) {
            if (properties.getTiers().containsKey(tier.trim())) {
                source = "TIER";
            } else {
                // 这就是那个陷阱：档位名不在配置里 → 静默按不限放行
                source = "UNLIMITED";
                warning = "套餐「" + tier + "」不存在，这个客户现在是【不限制】的。"
                        + (tiers.isEmpty()
                        ? "请直接在下面填数字。"
                        : "可选套餐：" + String.join("、", tiers));
            }
        } else {
            source = "UNLIMITED";
        }
        if (effective != null && effective == 0) {
            warning = "填 0 = 这个客户一个任务都提交不了（相当于停用）。想「不限制」请把输入框清空。";
        }
        if (properties.isShadow() && effective != null) {
            warning = (warning == null ? "" : warning + " ")
                    + "系统当前是试运行状态：客户超出时只做记录，不会真的拦下来。";
        }

        // 席位数 vs 接口提交速率。不匹配时席位数是个装饰品，而这件事页面上一个字都看不出来
        Integer ratePerMinute = null;
        Integer reachable = null;
        if (effective != null && effective > 0) {
            RateLimitConfig.Bucket bucket = apiBucketFor(effective);
            ratePerMinute = ratePerMinute(bucket);
            reachable = reachableInFlight(bucket);
            if (reachable < effective) {
                warning = (warning == null ? "" : warning + " ")
                        + "注意：这个客户设了 " + effective + " 个，但接口限速只放到每分钟 "
                        + ratePerMinute + " 次，实际最多只能跑到 " + reachable
                        + " 个。要么把这里改到 " + reachable + " 以内，要么让技术放宽接口限速。";
            }
        }
        return new ConcurrencyLimitView(tier, override, effective, source, warning, tiers,
                ratePerMinute, reachable);
    }

    /** 影子模式：照常记账和打日志，但不拒绝 */
    public boolean isShadow() {
        return properties.isShadow();
    }

    /** 一条任务合法能活的最长时间：每次重试都会重置超时基准，所以是「首投 + 重试次数」段 */
    public long maxTaskLifetimeSeconds() {
        return (long) taskTimeoutMinutes * 60L * (timeoutRetryMax + 1L);
    }

    /**
     * 老化窗口：登记超过这个时长的一律淘汰，是所有机制都失灵时防止幽灵永久占位的最后一道。
     * <p>
     * <b>推导而不是配死</b>：写死一个数的话，将来谁把 {@code video.task-timeout-minutes}
     * 从 60 调到 120，这道保险就会静默失效 —— 长任务还在跑就被淘汰、槽位提前释放，
     * 超发，而且恰好发生在系统最忙的时候。
     */
    public long agingWindowSeconds() {
        Long override = properties.getAgingWindowSecondsOverride();
        if (override != null) {
            return override;
        }
        return maxTaskLifetimeSeconds() * Math.max(properties.getAgingSafetyFactor(), 1);
    }

    /**
     * 启动即校验，配错不许等到运行时才发现。
     * <p>
     * 尤其是老化窗口小于任务寿命这一条：它不会报错、不会告警，只会在某个高峰期
     * 让一批还在跑的任务悄悄丢掉槽位。
     */
    @PostConstruct
    void validateAndReport() {
        if (taskTimeoutMinutes <= 0 || timeoutRetryMax < 0) {
            throw new IllegalStateException(
                    "video.task-timeout-minutes 必须为正、video.timeout-retry-max 不能为负，"
                            + "否则老化窗口推不出来: timeout=" + taskTimeoutMinutes
                            + ", retryMax=" + timeoutRetryMax);
        }
        if (properties.getAgingSafetyFactor() < 1) {
            throw new IllegalStateException(
                    "concurrency.aging-safety-factor 至少为 1，实际=" + properties.getAgingSafetyFactor());
        }
        if (properties.getAvgTaskMinutes() <= 0) {
            // 它是「席位数 ÷ 本值」的除数：配成 0 会直接 ArithmeticException/Infinity，
            // 而现场在 API 限流的热路径上，离配置很远
            throw new IllegalStateException(
                    "concurrency.avg-task-minutes 必须为正（它是把席位数换算成接口速率的除数），实际="
                            + properties.getAvgTaskMinutes());
        }
        long floor = maxTaskLifetimeSeconds();
        Long override = properties.getAgingWindowSecondsOverride();
        if (override != null && override < floor) {
            throw new IllegalStateException(
                    "concurrency.aging-window-seconds-override=" + override
                            + " 小于任务合法寿命 " + floor + "s（= task-timeout-minutes "
                            + taskTimeoutMinutes + " × (timeout-retry-max " + timeoutRetryMax
                            + " + 1) × 60）。这会让还在跑的任务被老化淘汰、槽位提前释放（超发），"
                            + "而且不报错、不告警。要么调大它，要么删掉这项让系统自己推导。");
        }
        properties.getTiers().forEach((tier, max) -> {
            if (max == null || max < 0) {
                throw new IllegalStateException("concurrency.tiers." + tier + " 必须 >= 0，实际=" + max);
            }
        });
        log.info("在途并发额度: enabled={}, shadow={}, 档位={}, 任务最长寿命={}s, 老化窗口={}s{}",
                properties.isEnabled(), properties.isShadow(), properties.getTiers(),
                floor, agingWindowSeconds(), override == null ? "（推导）" : "（人工覆盖）");
    }
}
