package org.example.seedancegenarate.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Select;
import org.example.seedancegenarate.entity.GenerationAttempt;

import java.util.List;

@Mapper
public interface GenerationAttemptMapper extends BaseMapper<GenerationAttempt> {

    /**
     * 只扫描尚未建恢复作业或恢复作业已耗尽的 UNKNOWN。
     * 未建作业优先，避免大量离线节点对应的 DEAD 作业挡住刚产生的 UNKNOWN。
     */
    @Select("SELECT a.* FROM generation_attempt a "
            + "LEFT JOIN async_job j ON j.job_type = 'GENERATION_RECOVERY' "
            + "AND j.biz_key = CONCAT('attempt:', a.id) "
            + "WHERE a.status = 'SUBMIT_UNKNOWN' "
            + "AND (j.id IS NULL OR j.status = 'DEAD') "
            + "ORDER BY (j.id IS NULL) DESC, a.id ASC LIMIT #{limit}")
    List<GenerationAttempt> findSubmitUnknown(@Param("limit") int limit);

    /** 只有 PENDING 能进入可能产生外部副作用的区间。 */
    @Update("UPDATE generation_attempt SET status = 'SUBMITTING', last_error = NULL "
            + "WHERE id = #{id} AND status = 'PENDING'")
    int markSubmitting(@Param("id") Long id);

    /** 明确未入队：回到 PENDING，可在退避后安全重投。 */
    @Update("UPDATE generation_attempt SET status = 'PENDING', last_error = #{lastError} "
            + ", node_id = NULL "
            + "WHERE id = #{id} AND status = 'SUBMITTING'")
    int markPending(@Param("id") Long id, @Param("lastError") String lastError);

    /** 实际节点必须在 /prompt 前落库；同一 SUBMITTING 轮次不允许中途换节点。 */
    @Update("UPDATE generation_attempt SET node_id = #{nodeId} "
            + "WHERE id = #{id} AND status = 'SUBMITTING' "
            + "AND (node_id IS NULL OR node_id = #{nodeId})")
    int rememberSubmittingNode(@Param("id") Long id, @Param("nodeId") String nodeId);

    /** SAFE_RETRY 已耗尽后的唯一收口；未知结果不得走这里。 */
    @Update("UPDATE generation_attempt SET status = 'FAILED', last_error = #{lastError} "
            + "WHERE id = #{id} AND status = 'PENDING'")
    int markFailed(@Param("id") Long id, @Param("lastError") String lastError);

    /** 管理员确认无法恢复后收口 UNKNOWN；必须与任务终态事务一起调用。 */
    @Update("UPDATE generation_attempt SET status = 'FAILED', last_error = #{lastError} "
            + "WHERE id = #{id} AND status = 'SUBMIT_UNKNOWN'")
    int markUnknownFailed(@Param("id") Long id, @Param("lastError") String lastError);

    /** 结果不可判定：只停放，不把任务判失败。 */
    @Update("UPDATE generation_attempt SET status = 'SUBMIT_UNKNOWN', last_error = #{lastError} "
            + "WHERE id = #{id} AND status = 'SUBMITTING'")
    int markUnknown(@Param("id") Long id, @Param("lastError") String lastError);

    /** 迟到成功可以收敛 SUBMIT_UNKNOWN，但后续任务回写仍受 current_attempt_id 约束。 */
    @Update("UPDATE generation_attempt SET status = 'SUBMITTED', provider_task_id = #{providerTaskId}, "
            + "node_id = #{nodeId}, last_error = NULL "
            + "WHERE id = #{id} AND status IN ('SUBMITTING', 'SUBMIT_UNKNOWN')")
    int markSubmitted(@Param("id") Long id,
                      @Param("providerTaskId") String providerTaskId,
                      @Param("nodeId") String nodeId);
}
