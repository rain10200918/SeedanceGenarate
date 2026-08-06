package org.example.seedancegenarate.engine;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 视频生成引擎注册表。Spring 自动注入所有 {@link VideoEngine} 实现并按 provider 建索引。
 * 选择策略 = 按 key 取；新增提供方无需改这里。
 */
@Component
public class VideoEngineRegistry {

    private final Map<String, VideoEngine> engines;

    public VideoEngineRegistry(List<VideoEngine> engineList) {
        this.engines = engineList.stream()
                .collect(Collectors.toMap(VideoEngine::provider, Function.identity()));
    }

    public VideoEngine get(String provider) {
        VideoEngine engine = engines.get(provider);
        if (engine == null) {
            throw new RuntimeException("不支持的视频生成提供方: " + provider);
        }
        return engine;
    }

    /** 全部已注册引擎，供 /options 聚合下发。 */
    public Collection<VideoEngine> all() {
        return engines.values();
    }
}
