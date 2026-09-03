package org.example.seedancegenarate.service.llm;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.seedancegenarate.config.PromptOptimizeConfig;
import org.example.seedancegenarate.entity.LlmChannel;
import org.example.seedancegenarate.mapper.LlmChannelMapper;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * LLM 通道清单的<b>唯一来源</b>，带 30 秒缓存。换/加一个第三方不再需要改 yaml + 重启。
 * 和 {@code ComfyNodeRegistry} 是同一个形状 —— 运维看到的失效边界、降级行为完全一致。
 *
 * <h3>yaml 从此只是 seed</h3>
 * 启动时把 {@code prompt-optimize} 那一份配置<b>只 INSERT、不 UPDATE</b> 成名为 {@value #SEED_NAME} 的通道。
 * 之后改 yaml 不生效，要改就在管理端改。upsert 写法会让人的每一次修改在下次重启时静默失效。
 * <p>
 * seed 走代码不走迁移 SQL：yaml 里的地址和密钥是环境变量占位符，写死进 SQL 会把开发默认值灌进生产。
 *
 * <h3>降级链：表 → 上一次成功的清单 → yaml</h3>
 * 「AI 润色」在用户请求路径上，MySQL 抖一下不能让它整个不可用。查库失败保留上一次成功的结果；
 * 只有「进程刚起来就连不上库」才回落 yaml。{@code everLoaded} 与 {@code cachedAt} 必须分开 ——
 * 合用一个哨兵的话，管理端 invalidate 之后紧接着库抖一下就会退回 yaml，把库里的通道整个换掉。
 *
 * <h3>归档的通道仍然在清单里</h3>
 * 只有路由和管理端默认列表会跳过它。清单本身含归档行，试跑才能指到关闭/归档的通道。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LlmChannelRegistry {

    public static final String SEED_NAME = "default";
    /** 加通道是人点按钮的低频动作。改完管理端会主动 invalidate */
    public static final long CACHE_MS = 30_000L;

    private final LlmChannelMapper llmChannelMapper;
    private final PromptOptimizeConfig config;

    private volatile List<LlmChannelSpec> cache = List.of();
    private volatile long cachedAt;
    private volatile boolean everLoaded;

    @PostConstruct
    void seedFromYaml() {
        if (!yamlConfigured()) {
            log.info("LLM 通道清单来自数据库；application.yaml 的 prompt-optimize 未配置，不做 seed");
            return;
        }
        try {
            if (llmChannelMapper.selectById(SEED_NAME) == null) {
                LlmChannel row = new LlmChannel();
                row.setName(SEED_NAME);
                row.setBaseUrl(config.getUrl().trim());
                row.setApiKey(config.getApiKey().trim());
                row.setModel(config.getModel().trim());
                row.setTemperature(config.getTemperature() == null ? null : BigDecimal.valueOf(config.getTemperature()));
                row.setMaxTokens(config.getMaxTokens());
                row.setTokenParam(LlmChannelSpec.TokenParam.MAX_TOKENS.stored());
                row.setTimeoutMs(config.getTimeoutMs());
                row.setPriority(100);
                // seed 保留「启用」：这是升级前就在服务用户的那条，一律置 false 会让升级把 AI 润色整个关掉
                row.setEnabled(true);
                row.setArchived(false);
                row.setRemark("从 application.yaml 首次导入");
                llmChannelMapper.insert(row);
                log.info("LLM 通道首次入库: {} -> model={}", SEED_NAME, row.getModel());
            }
        } catch (Exception e) {
            // 迁移还没跑 / 库不通 —— 不能拦着应用起来，降级链会回落 yaml
            log.warn("LLM 通道 seed 失败（将回落 yaml）: {}", e.getMessage());
        }
        log.info("LLM 通道清单来自数据库；application.yaml 的 prompt-optimize 只在首次 seed 时使用，改它不生效");
    }

    /** 全部通道（<b>含归档、含停用</b>），按 priority 再按名字排。管理端和试跑走这里 */
    public List<LlmChannelSpec> channels() {
        long now = System.currentTimeMillis();
        if (everLoaded && cachedAt > 0 && now - cachedAt < CACHE_MS) {
            return cache;
        }
        try {
            List<LlmChannelSpec> fresh = query();
            cache = fresh;
            cachedAt = now;
            everLoaded = true;
            return fresh;
        } catch (Exception e) {
            if (everLoaded) {
                log.warn("LLM 通道清单查询失败，沿用上一次的 {} 条: {}", cache.size(), e.getMessage());
                return cache;
            }
            log.error("LLM 通道清单查询失败且无缓存，回落 application.yaml: {}", e.getMessage());
            return yamlFallback();
        }
    }

    /** 参与路由的：启用且未归档，priority 小的在前 */
    public List<LlmChannelSpec> routable() {
        return channels().stream().filter(LlmChannelSpec::routable).toList();
    }

    /** 按名字找（含归档、含停用）；没有返回 null */
    public LlmChannelSpec find(String name) {
        if (name == null) {
            return null;
        }
        return channels().stream().filter(c -> c.name().equals(name)).findFirst().orElse(null);
    }

    /**
     * 管理端改完立刻调。<b>只作废新鲜度，不动 everLoaded</b> ——
     * 否则「改完通道」紧接着「库抖了一下」就会退回 yaml，而不是沿用刚才那份。
     */
    public void invalidate() {
        cachedAt = 0;
    }

    private List<LlmChannelSpec> query() {
        List<LlmChannelSpec> result = new ArrayList<>();
        for (LlmChannel row : llmChannelMapper.selectList(Wrappers.<LlmChannel>lambdaQuery())) {
            result.add(toSpec(row));
        }
        result.sort(Comparator.comparingInt(LlmChannelSpec::priority).thenComparing(LlmChannelSpec::name));
        return List.copyOf(result);
    }

    static LlmChannelSpec toSpec(LlmChannel row) {
        return new LlmChannelSpec(
                row.getName(),
                row.getBaseUrl(),
                row.getApiKey(),
                row.getModel(),
                row.getTemperature() == null ? null : row.getTemperature().doubleValue(),
                row.getMaxTokens() == null ? 1500 : row.getMaxTokens(),
                LlmChannelSpec.TokenParam.parse(row.getTokenParam()),
                row.getTimeoutMs() == null ? 100_000 : row.getTimeoutMs(),
                row.getPriority() == null ? 100 : row.getPriority(),
                Boolean.TRUE.equals(row.getEnabled()),
                Boolean.TRUE.equals(row.getArchived()),
                row.getRemark());
    }

    private boolean yamlConfigured() {
        return config.getUrl() != null && !config.getUrl().isBlank()
                && config.getApiKey() != null && !config.getApiKey().isBlank()
                && config.getModel() != null && !config.getModel().isBlank();
    }

    private List<LlmChannelSpec> yamlFallback() {
        if (!yamlConfigured()) {
            return List.of();
        }
        return List.of(new LlmChannelSpec(
                SEED_NAME,
                config.getUrl().trim(),
                config.getApiKey().trim(),
                config.getModel().trim(),
                config.getTemperature(),
                config.getMaxTokens() == null ? 1500 : config.getMaxTokens(),
                LlmChannelSpec.TokenParam.MAX_TOKENS,
                config.getTimeoutMs() == null ? 100_000 : config.getTimeoutMs(),
                100, true, false, "yaml 回落"));
    }
}
