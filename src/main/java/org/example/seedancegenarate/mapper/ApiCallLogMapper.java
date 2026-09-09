package org.example.seedancegenarate.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.example.seedancegenarate.entity.ApiCallLog;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Mapper
public interface ApiCallLogMapper extends BaseMapper<ApiCallLog> {

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
