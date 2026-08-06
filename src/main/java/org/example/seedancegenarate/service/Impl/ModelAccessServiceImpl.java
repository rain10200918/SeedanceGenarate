package org.example.seedancegenarate.service.Impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.example.seedancegenarate.dto.ModelAccessView;
import org.example.seedancegenarate.engine.VideoEngineRegistry;
import org.example.seedancegenarate.entity.ModelAccess;
import org.example.seedancegenarate.mapper.ModelAccessMapper;
import org.example.seedancegenarate.service.ModelAccessService;
import org.springframework.beans.factory.annotation.Value;
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
 */
@Service
public class ModelAccessServiceImpl implements ModelAccessService {

    private final VideoEngineRegistry videoEngineRegistry;
    private final ModelAccessMapper modelAccessMapper;

    /** 无显式覆盖时的默认开关：true=新模型默认开放 */
    @Value("${video.model-access.default-open:true}")
    private boolean defaultOpen;

    public ModelAccessServiceImpl(VideoEngineRegistry videoEngineRegistry, ModelAccessMapper modelAccessMapper) {
        this.videoEngineRegistry = videoEngineRegistry;
        this.modelAccessMapper = modelAccessMapper;
    }

    @Override
    public boolean isOpen(String model) {
        if (!StringUtils.hasText(model)) {
            return true; // 未指定具体模型（引擎用默认模型）：不在此拦截
        }
        ModelAccess row = modelAccessMapper.selectOne(
                Wrappers.<ModelAccess>lambdaQuery().eq(ModelAccess::getModel, model));
        return (row == null || row.getEnabled() == null) ? defaultOpen : row.getEnabled();
    }

    @Override
    public List<ModelAccessView> listAll() {
        Map<String, Boolean> overrides = modelAccessMapper
                .selectList(Wrappers.<ModelAccess>lambdaQuery())
                .stream()
                .filter(row -> row.getModel() != null && row.getEnabled() != null)
                .collect(Collectors.toMap(ModelAccess::getModel, ModelAccess::getEnabled, (a, b) -> b));
        return videoEngineRegistry.all().stream()
                .flatMap(engine -> engine.models().stream()
                        .map(spec -> new ModelAccessView(
                                engine.provider(),
                                spec.model(),
                                spec.label(),
                                spec.outputType().name(),
                                overrides.getOrDefault(spec.model(), defaultOpen))))
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
    }
}
