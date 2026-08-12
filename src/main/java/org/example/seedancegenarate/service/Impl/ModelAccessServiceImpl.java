package org.example.seedancegenarate.service.Impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.example.seedancegenarate.config.ConfigCacheProperties;
import org.example.seedancegenarate.dto.ModelAccessView;
import org.example.seedancegenarate.engine.VideoEngineRegistry;
import org.example.seedancegenarate.entity.ModelAccess;
import org.example.seedancegenarate.mapper.ModelAccessMapper;
import org.example.seedancegenarate.service.ConfigInvalidationNotifier;
import org.example.seedancegenarate.service.ConfigSnapshotReloadable;
import org.example.seedancegenarate.service.ModelAccessService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 模型开放策略实现：稀疏覆盖表 + 默认值。
 * <ul>
 *   <li>{@code model_access} 表只存管理员显式设过的模型；没有行 → 走默认。</li>
 *   <li>默认开关来自 {@code video.model-access.default-open}（默认 true，即新模型自动开放，保持「加模型零配置即可见」）。</li>
 *   <li>模型集合以 {@link VideoEngineRegistry} 为准，表只叠加开关，避免注册表与表漂移。</li>
 * </ul>
 * <p>
 * 这张表极小（只存显式覆盖）且极少变（只有管理员开关时变），所以整表读进进程内快照：
 * {@code isOpen()} 读内存、零库查询。原先每次查一行，而 {@code /options} 对每个模型
 * 各调一次 —— 一次请求就是七八条单行查询。
 * <p>
 * 一致性：{@code setOpen} 写库后立刻刷新本实例并广播失效（多实例毫秒级一致），
 * 另有定时兜底重载（广播丢失时最多滞后一个周期）。{@code cache.config.enabled=false}
 * 时退回每次直查，行为与加缓存前一致。
 */
@Slf4j
@Service
public class ModelAccessServiceImpl implements ModelAccessService, ConfigSnapshotReloadable {

    private final VideoEngineRegistry videoEngineRegistry;
    private final ModelAccessMapper modelAccessMapper;
    private final ConfigCacheProperties cacheProperties;
    private final ConfigInvalidationNotifier invalidationNotifier;

    /** model → enabled 全量覆盖快照。整体替换而非逐键改，读侧无需加锁。 */
    private volatile Map<String, Boolean> overrides = Map.of();

    /** 无显式覆盖时的默认开关：true=新模型默认开放 */
    @Value("${video.model-access.default-open:true}")
    private boolean defaultOpen;

    public ModelAccessServiceImpl(VideoEngineRegistry videoEngineRegistry,
                                  ModelAccessMapper modelAccessMapper,
                                  ConfigCacheProperties cacheProperties,
                                  ConfigInvalidationNotifier invalidationNotifier) {
        this.videoEngineRegistry = videoEngineRegistry;
        this.modelAccessMapper = modelAccessMapper;
        this.cacheProperties = cacheProperties;
        this.invalidationNotifier = invalidationNotifier;
    }

    @PostConstruct
    public void init() {
        reload();
    }

    @Override
    public String snapshotType() {
        return ConfigInvalidationNotifier.TYPE_MODEL_ACCESS;
    }

    /** 兜底重载：正常由失效广播即时触发，这里只在广播丢失或未启用广播时接管。 */
    @Override
    @Scheduled(fixedDelayString = "${cache.config.reload-interval-ms:60000}")
    public void reload() {
        if (!cacheProperties.getConfig().isEnabled()) {
            return;
        }
        try {
            overrides = loadOverrides();
        } catch (Exception e) {
            // 保留上一份快照：读到空覆盖会让所有模型走默认值（可能是全开），比用旧值危险
            log.warn("重载模型开关快照失败，保留上一份: {}", e.getMessage());
        }
    }

    @Override
    public boolean isOpen(String model) {
        if (!StringUtils.hasText(model)) {
            return true; // 未指定具体模型（引擎用默认模型）：不在此拦截
        }
        return currentOverrides().getOrDefault(model, defaultOpen);
    }

    @Override
    public Map<String, Boolean> currentOverrides() {
        return cacheProperties.getConfig().isEnabled() ? overrides : loadOverrides();
    }

    @Override
    public boolean defaultOpen() {
        return defaultOpen;
    }

    @Override
    public List<ModelAccessView> listAll() {
        Map<String, Boolean> current = currentOverrides();
        return videoEngineRegistry.all().stream()
                .flatMap(engine -> engine.models().stream()
                        .map(spec -> new ModelAccessView(
                                engine.provider(),
                                spec.model(),
                                spec.label(),
                                spec.outputType().name(),
                                current.getOrDefault(spec.model(), defaultOpen))))
                .toList();
    }

    @Override
    public void setOpen(String model, boolean open) {
        if (!StringUtils.hasText(model)) {
            throw new RuntimeException("模型标识不能为空");
        }
        ModelAccess existing = modelAccessMapper.selectOne(
                Wrappers.<ModelAccess>lambdaQuery().eq(ModelAccess::getModel, model));
        if (existing == null) {
            ModelAccess row = new ModelAccess();
            row.setModel(model);
            row.setEnabled(open);
            modelAccessMapper.insert(row);
        } else {
            existing.setEnabled(open);
            modelAccessMapper.updateById(existing);
        }
        // 先刷本实例（管理员自己下一个请求就该看到新值），再广播给其他实例
        reload();
        invalidationNotifier.notifyChanged(ConfigInvalidationNotifier.TYPE_MODEL_ACCESS);
    }

    private Map<String, Boolean> loadOverrides() {
        return modelAccessMapper.selectList(Wrappers.<ModelAccess>lambdaQuery())
                .stream()
                .filter(row -> row.getModel() != null && row.getEnabled() != null)
                .collect(Collectors.toMap(ModelAccess::getModel, ModelAccess::getEnabled, (a, b) -> b));
    }
}
