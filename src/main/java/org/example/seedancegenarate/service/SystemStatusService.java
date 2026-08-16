package org.example.seedancegenarate.service;

import org.example.seedancegenarate.dto.SystemStatus;

/**
 * 管理端「系统状态」：运行健康度聚合，DB 现查（不依赖 Prometheus）。
 */
public interface SystemStatusService {

    SystemStatus current();
}
