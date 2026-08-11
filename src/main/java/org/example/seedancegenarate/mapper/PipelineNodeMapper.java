package org.example.seedancegenarate.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.example.seedancegenarate.entity.PipelineNode;

@Mapper
public interface PipelineNodeMapper extends BaseMapper<PipelineNode> {

    /**
     * 原子占位：PENDING / FAILED → PROCESSING。影响 1 行才允许提交，
     * 防止两个 Worker（或重试与并发 run）同时提交同一节点生成两次。
     */
    @Update("UPDATE pipeline_node SET status = 'PROCESSING' WHERE id = #{id} AND status IN ('PENDING', 'FAILED')")
    int occupyForSubmit(@Param("id") Long id);
}
