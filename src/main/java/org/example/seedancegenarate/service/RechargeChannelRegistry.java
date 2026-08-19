package org.example.seedancegenarate.service;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 资金渠道策略注册表（与 {@code VideoEngineRegistry} 同构）：Spring 收集全部
 * {@link RechargeChannelAdapter}，按 channel() 路由。新增渠道 = 新增一个 @Component，零改动。
 */
@Component
public class RechargeChannelRegistry {

    private final Map<String, RechargeChannelAdapter> adapters;

    public RechargeChannelRegistry(List<RechargeChannelAdapter> adapters) {
        this.adapters = adapters.stream()
                .collect(Collectors.toMap(RechargeChannelAdapter::channel, Function.identity()));
    }

    public RechargeChannelAdapter get(String channel) {
        RechargeChannelAdapter adapter = adapters.get(channel);
        if (adapter == null) {
            throw new IllegalArgumentException("不支持的充值渠道: " + channel);
        }
        return adapter;
    }
}
