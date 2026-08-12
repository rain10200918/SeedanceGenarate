package org.example.seedancegenarate.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 请求路径缓存与写合并的开关。
 * <p>
 * 全部默认开启；任一项出问题可单独关掉回退到「每请求直查 / 直写」的原行为，
 * 不需要回滚代码。关掉后行为与本次改动前一致（验收项之一）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "cache")
public class ConfigCacheProperties {

    /** 最后活跃信息（IP / 最后操作）写合并：按 userId 合并后定时批量落库。 */
    private Activity activity = new Activity();

    /** 模型开放开关与计价配置的进程内快照。 */
    private ConfigSnapshot config = new ConfigSnapshot();

    /** 管理端聚合统计的短 TTL 缓存。 */
    private Stats stats = new Stats();

    @Data
    public static class Activity {
        private boolean enabled = true;
        /**
         * 批量落库间隔。进程被 kill -9 时最多丢这么久的「最后活跃」记录——
         * 登录时间不走缓冲（直写），不受影响。
         */
        private long flushIntervalMs = 5_000;
    }

    @Data
    public static class ConfigSnapshot {
        private boolean enabled = true;
        /**
         * 兜底重载周期。正常由失效广播即时重载；此项只在广播丢失或未启用
         * {@code feature.redis-config-invalidation} 时接管。
         */
        private long reloadIntervalMs = 60_000;
    }

    @Data
    public static class Stats {
        private boolean enabled = true;
        /** 聚合结果缓存时长；后台看到的数字最多滞后这么久。 */
        private long ttlMs = 30_000;
    }
}
