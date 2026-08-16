package org.example.seedancegenarate.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.example.seedancegenarate.entity.PromptTokenUsage;

import java.util.List;
import java.util.Map;

@Mapper
public interface PromptTokenUsageMapper extends BaseMapper<PromptTokenUsage> {

    @Select("SELECT COALESCE(COUNT(*), 0) AS calls, COALESCE(SUM(prompt_tokens), 0) AS input_tokens, "
            + "COALESCE(SUM(completion_tokens), 0) AS output_tokens, COALESCE(SUM(total_tokens), 0) AS total_tokens "
            + "FROM prompt_token_usage WHERE status = 'SUCCESS' AND create_time >= #{since}")
    Map<String, Object> sumSince(@Param("since") String since);

    @Select("SELECT COALESCE(COUNT(*), 0) AS calls FROM prompt_token_usage WHERE status = 'FAILED' AND create_time >= #{since}")
    long failedCallsSince(@Param("since") String since);

    @Select("SELECT scene, COUNT(*) AS calls, COALESCE(SUM(total_tokens), 0) AS tokens "
            + "FROM prompt_token_usage WHERE status = 'SUCCESS' GROUP BY scene ORDER BY tokens DESC")
    List<Map<String, Object>> groupByScene();

    @Select("SELECT DATE_FORMAT(create_time, '%Y-%m-%d') AS date, COUNT(*) AS calls, "
            + "COALESCE(SUM(total_tokens), 0) AS tokens "
            + "FROM prompt_token_usage WHERE status = 'SUCCESS' AND create_time >= #{since} "
            + "GROUP BY DATE_FORMAT(create_time, '%Y-%m-%d') ORDER BY date")
    List<Map<String, Object>> groupByDay(@Param("since") String since);
}
