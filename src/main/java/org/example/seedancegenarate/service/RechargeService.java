package org.example.seedancegenarate.service;

import org.example.seedancegenarate.service.RechargeChannelAdapter.RechargeCommand;

/**
 * 资金流入入口：按渠道路由到对应策略。调用点只认识 RechargeService，不认识具体渠道。
 */
public interface RechargeService {

    /** 按渠道入账（channel 见 {@link RechargeChannelAdapter#channel()}） */
    void recharge(String channel, RechargeCommand command);
}
