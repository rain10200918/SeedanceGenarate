package org.example.seedancegenarate.service;

import org.example.seedancegenarate.dto.ApiKeyUserView;
import org.example.seedancegenarate.dto.UserStatsResponse;

import java.util.List;

/**
 * 个人中心数据：统计 / 我的 API Key / 修改密码。全部由现有表聚合现算。
 */
public interface UserProfileService {

    /** 消费与任务统计（累计/本月/成功率/近 7 天趋势/按模型分布/最近记录） */
    UserStatsResponse stats(Long userId);

    /** 当前用户名下的 API Key + 调用聚合 */
    List<ApiKeyUserView> apiKeys(Long userId);

    /** 修改密码：校验旧密码后更新（BCrypt） */
    void changePassword(Long userId, String oldPassword, String newPassword);
}
