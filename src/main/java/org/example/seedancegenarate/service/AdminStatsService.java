package org.example.seedancegenarate.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.example.seedancegenarate.dto.AdminDashboardResponse;
import org.example.seedancegenarate.dto.ApiCallLogView;
import org.example.seedancegenarate.dto.ApiCallSummary;
import org.example.seedancegenarate.dto.UserProfileDetail;
import org.example.seedancegenarate.dto.UserSummary;

/**
 * 管理端统计：全局看板 / API 调用明细与汇总。全部聚合现算。
 */
public interface AdminStatsService {

    /** 全局看板（全部用户） */
    AdminDashboardResponse dashboard();

    /** API 调用明细（分页 + 可选过滤），返回带钥匙前缀/属主用户名的视图 */
    Page<ApiCallLogView> apiCalls(long current, long size, Long apiKeyId, String model, String provider, String status, String errorCode);

    /** API 调用汇总：状态分布 + 拒绝原因分布（可选按钥匙过滤） */
    ApiCallSummary apiCallSummary(Long apiKeyId);

    /** 用户管理页全局统计卡（全量聚合） */
    UserSummary userSummary();

    /** 单个用户画像详情（任务/API 状态分布 + 消费 + 最近记录） */
    UserProfileDetail userDetail(Long userId);
}
