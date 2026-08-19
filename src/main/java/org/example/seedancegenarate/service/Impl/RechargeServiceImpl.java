package org.example.seedancegenarate.service.Impl;

import org.example.seedancegenarate.service.RechargeChannelAdapter;
import org.example.seedancegenarate.service.RechargeChannelRegistry;
import org.example.seedancegenarate.service.RechargeService;
import org.example.seedancegenarate.service.RechargeChannelAdapter.RechargeCommand;
import org.springframework.stereotype.Service;

/**
 * 资金流入入口：委托注册表按 channel 路由。策略注册表见 {@link RechargeChannelRegistry}。
 */
@Service
public class RechargeServiceImpl implements RechargeService {

    private final RechargeChannelRegistry registry;

    public RechargeServiceImpl(RechargeChannelRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void recharge(String channel, RechargeCommand command) {
        registry.get(channel).recharge(command);
    }
}
