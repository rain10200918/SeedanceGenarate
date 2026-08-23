package org.example.seedancegenarate.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.example.seedancegenarate.entity.VideoTask;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Mapper
public interface VideoTaskMapper extends BaseMapper<VideoTask> {

    /** 终态账务补偿候选：缺少对应 SETTLE/RELEASE 流水；限制最近天数范围，配合 idx_bt_task_type 索引消除全表扫描。 */
    @Select("SELECT v.* FROM video_task v "
            + "WHERE v.status IN ('SUCCESS', 'FAILED') "
            + "AND v.freeze_amount IS NOT NULL AND v.freeze_amount > 0 "
            + "AND v.create_time >= #{since} "
            + "AND NOT EXISTS (SELECT 1 FROM balance_transaction b "
            + "WHERE b.task_id = v.id AND b.type = CASE WHEN v.status = 'SUCCESS' THEN 'SETTLE' ELSE 'RELEASE' END) "
            + "ORDER BY v.id ASC LIMIT #{limit}")
    List<VideoTask> findTerminalMissingWalletTransitionSince(@Param("since") LocalDateTime since, @Param("limit") int limit);

    /** 默认重载：扫描最近 7 天内任务 */
    default List<VideoTask> findTerminalMissingWalletTransition(int limit) {
        return findTerminalMissingWalletTransitionSince(LocalDateTime.now().minusDays(7), limit);
    }

    /** 管理端看板：总数/今日/本月概览汇总（单条 SQL 聚合，避免全表拉取到内存） */
    @Select("SELECT "
            + "COUNT(*) AS totalTasks, "
            + "COALESCE(SUM(CASE WHEN status = 'SUCCESS' THEN 1 ELSE 0 END), 0) AS totalSuccess, "
            + "COALESCE(SUM(CASE WHEN status = 'FAILED' THEN 1 ELSE 0 END), 0) AS totalFailed, "
            + "COALESCE(SUM(CASE WHEN create_time >= #{todayStart} THEN 1 ELSE 0 END), 0) AS todayTasks, "
            + "COALESCE(SUM(CASE WHEN create_time >= #{todayStart} AND status = 'SUCCESS' THEN 1 ELSE 0 END), 0) AS todaySuccess, "
            + "COALESCE(SUM(CASE WHEN create_time >= #{todayStart} AND status = 'FAILED' THEN 1 ELSE 0 END), 0) AS todayFailed, "
            + "COALESCE(SUM(CASE WHEN create_time >= #{todayStart} THEN cost_amount ELSE 0 END), 0) AS todayCost, "
            + "COALESCE(SUM(CASE WHEN create_time >= #{monthStart} THEN cost_amount ELSE 0 END), 0) AS monthCost "
            + "FROM video_task")
    Map<String, Object> selectDashboardOverview(@Param("todayStart") LocalDateTime todayStart,
                                                @Param("monthStart") LocalDateTime monthStart);

    /** 管理端看板：近 7 天每日生成数（按日分组聚合） */
    @Select("SELECT DATE_FORMAT(create_time, '%Y-%m-%d') AS date, COUNT(*) AS count "
            + "FROM video_task "
            + "WHERE create_time >= #{startDate} "
            + "GROUP BY DATE_FORMAT(create_time, '%Y-%m-%d') "
            + "ORDER BY date ASC")
    List<Map<String, Object>> selectDailyTrend(@Param("startDate") LocalDateTime startDate);

    /** 管理端看板：按模型聚合生成数与消费金额 */
    @Select("SELECT COALESCE(model, '未知') AS model, COUNT(*) AS count, COALESCE(SUM(cost_amount), 0) AS cost "
            + "FROM video_task "
            + "GROUP BY model "
            + "ORDER BY count DESC")
    List<Map<String, Object>> selectModelStats();

    /** 管理端看板：消费 TOP 用户（前 10） */
    @Select("SELECT user_id AS userId, COALESCE(SUM(cost_amount), 0) AS totalCost "
            + "FROM video_task "
            + "WHERE user_id IS NOT NULL "
            + "GROUP BY user_id "
            + "ORDER BY totalCost DESC "
            + "LIMIT 10")
    List<Map<String, Object>> selectTopUsers();
}
