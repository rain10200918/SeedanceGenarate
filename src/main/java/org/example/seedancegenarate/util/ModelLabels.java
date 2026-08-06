package org.example.seedancegenarate.util;

import org.example.seedancegenarate.engine.ModelSpec;
import org.example.seedancegenarate.engine.VideoEngineRegistry;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * 模型标识 → 展示名 的映射（跨页面统计共用，来源唯一：VideoEngineRegistry）。
 */
public final class ModelLabels {

    private ModelLabels() {
    }

    public static Map<String, String> of(VideoEngineRegistry registry) {
        return registry.all().stream()
                .flatMap(engine -> engine.models().stream())
                .collect(Collectors.toMap(ModelSpec::model, ModelSpec::label, (a, b) -> a));
    }
}
