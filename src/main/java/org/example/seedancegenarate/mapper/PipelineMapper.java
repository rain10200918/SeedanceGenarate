package org.example.seedancegenarate.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.example.seedancegenarate.entity.Pipeline;

@Mapper
public interface PipelineMapper extends BaseMapper<Pipeline> {

    /** 原子状态门：DRAFT / PARTIAL_FAILED → RUNNING，只允许一次成功，防并发重复 run。 */
    @Update("UPDATE pipeline SET status = 'RUNNING' WHERE id = #{id} AND status IN ('DRAFT', 'PARTIAL_FAILED')")
    int markRunning(@Param("id") Long id);
}
