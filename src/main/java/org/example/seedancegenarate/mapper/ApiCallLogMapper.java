package org.example.seedancegenarate.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.example.seedancegenarate.entity.ApiCallLog;

import java.util.List;
import java.util.Map;

@Mapper
public interface ApiCallLogMapper extends BaseMapper<ApiCallLog> {

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
