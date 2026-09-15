package org.example.seedancegenarate.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.example.seedancegenarate.entity.ApiCallLog;
import org.example.seedancegenarate.dto.UserApiCallQuery;
import org.example.seedancegenarate.dto.UserApiCallView;
import org.example.seedancegenarate.dto.UserApiCallSummary;
import java.time.LocalDateTime;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Mapper
public interface ApiCallLogMapper extends BaseMapper<ApiCallLog> {

    // Every user query uses this owner predicate, including both summary aggregates.
    String USER_CALL_FILTER = " WHERE l.user_id = #{ownerId} "
            + "<if test='query.apiKeyId != null'> AND l.api_key_id = #{query.apiKeyId} "
            + "AND EXISTS (SELECT 1 FROM api_key owned WHERE owned.id = #{query.apiKeyId} AND owned.user_id = #{ownerId}) </if>"
            + "<if test='query.model != null'> AND l.model = #{query.model} </if>"
            + "<if test='query.provider != null'> AND l.provider = #{query.provider} </if>"
            + "<if test='query.status != null'> AND l.status = #{query.status} </if>"
            + "<if test='query.errorCode != null'> AND l.error_code = #{query.errorCode} </if>"
            + "<if test='from != null'> AND l.create_time &gt;= #{from} </if>"
            + "<if test='to != null'> AND l.create_time &lt; #{to} </if>";

    @Select("<script>SELECT l.id,l.request_id,l.task_id,l.api_key_id,k.name AS api_key_name,k.key_prefix,"
            + "l.model,l.provider,l.status,l.http_code,l.error_code,l.cost_amount,'CNY' AS currency,"
            + "l.create_time,l.update_time,l.queued_ms,l.generate_ms,l.total_ms "
            + "FROM api_call_log l LEFT JOIN api_key k ON k.id=l.api_key_id AND k.user_id=l.user_id "
            + USER_CALL_FILTER + " ORDER BY l.id DESC LIMIT #{limit} OFFSET #{offset}</script>")
    List<UserApiCallView> selectUserCalls(@Param("ownerId") long ownerId, @Param("query") UserApiCallQuery query,
                                        @Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
                                        @Param("offset") long offset, @Param("limit") long limit);

    @Select("<script>SELECT COUNT(*) FROM api_call_log l " + USER_CALL_FILTER + "</script>")
    long countUserCalls(@Param("ownerId") long ownerId, @Param("query") UserApiCallQuery query,
                       @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Select("<script>SELECT COUNT(*) AS total,"
            + "COALESCE(SUM(CASE WHEN l.status='RECEIVED' THEN 1 ELSE 0 END),0) AS received,"
            + "COALESCE(SUM(CASE WHEN l.status='SUCCESS' THEN 1 ELSE 0 END),0) AS success,"
            + "COALESCE(SUM(CASE WHEN l.status='FAILED' THEN 1 ELSE 0 END),0) AS failed,"
            + "COALESCE(SUM(CASE WHEN l.status='REJECTED' THEN 1 ELSE 0 END),0) AS rejected,"
            + "COALESCE(SUM(l.cost_amount),0) AS totalCost FROM api_call_log l " + USER_CALL_FILTER + "</script>")
    Map<String,Object> selectUserTotals(@Param("ownerId") long ownerId, @Param("query") UserApiCallQuery query,
                                        @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Select("<script>SELECT l.error_code AS errorCode,COUNT(*) AS count FROM api_call_log l "
            + USER_CALL_FILTER + " AND l.error_code IS NOT NULL AND l.error_code != '' "
            + "GROUP BY l.error_code ORDER BY count DESC,l.error_code ASC</script>")
    List<UserApiCallSummary.ErrorCodeCount> selectUserErrorCounts(
            @Param("ownerId") long ownerId, @Param("query") UserApiCallQuery query,
            @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** 按幂等键补任务关联；已有不同 taskId 时拒绝覆盖。 */
    @Update("<script>"
            + "UPDATE api_call_log SET task_id = #{taskId} "
            + "<if test='queuedMs != null'>, queued_ms = #{queuedMs}</if> "
            + "WHERE id = #{id} AND request_id = #{requestId} AND api_key_id = #{apiKeyId} "
            + "AND (task_id IS NULL OR task_id = #{taskId})"
            + "</script>")
    int linkTaskByRequestId(@Param("id") Long id,
                            @Param("apiKeyId") Long apiKeyId,
                            @Param("requestId") String requestId,
                            @Param("taskId") String taskId,
                            @Param("queuedMs") Long queuedMs);

    /** 快速 Worker 先终态时的条件收尾；事件监听器已收尾则影响 0 行。 */
    @Update("<script>"
            + "UPDATE api_call_log SET status = #{status}, error_msg = #{errorMsg} "
            + "<if test='costAmount != null'>, cost_amount = #{costAmount}</if> "
            + "<if test='totalMs != null'>, total_ms = #{totalMs}</if> "
            + "WHERE id = #{id} AND task_id = #{taskId} AND status = 'RECEIVED'"
            + "</script>")
    int finishReceived(@Param("id") Long id,
                       @Param("taskId") String taskId,
                       @Param("status") String status,
                       @Param("errorMsg") String errorMsg,
                       @Param("costAmount") BigDecimal costAmount,
                       @Param("totalMs") Long totalMs);

    /** API 调用汇总：状态分布（单条 SQL 聚合，避免全表 selectList 拉入内存） */
    @Select("<script>"
            + "SELECT status, COUNT(*) AS count FROM api_call_log "
            + "<where>"
            + "<if test='apiKeyId != null'> api_key_id = #{apiKeyId} </if>"
            + "</where>"
            + "GROUP BY status"
            + "</script>")
    List<Map<String, Object>> selectStatusCounts(@Param("apiKeyId") Long apiKeyId);

    /** API 调用汇总：按错误码分布（单条 SQL 聚合） */
    @Select("<script>"
            + "SELECT error_code AS errorCode, COUNT(*) AS count FROM api_call_log "
            + "WHERE error_code IS NOT NULL AND error_code != '' "
            + "<if test='apiKeyId != null'> AND api_key_id = #{apiKeyId} </if>"
            + "GROUP BY error_code "
            + "ORDER BY count DESC"
            + "</script>")
    List<Map<String, Object>> selectErrorCodeCounts(@Param("apiKeyId") Long apiKeyId);
}
