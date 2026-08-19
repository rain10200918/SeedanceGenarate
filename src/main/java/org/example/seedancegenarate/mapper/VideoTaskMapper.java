package org.example.seedancegenarate.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.example.seedancegenarate.entity.VideoTask;

import java.util.List;

@Mapper
public interface VideoTaskMapper extends BaseMapper<VideoTask> {

    /** 终态账务补偿候选：缺少对应 SETTLE/RELEASE 流水；唯一键最终幂等。 */
    @Select("SELECT v.* FROM video_task v "
            + "WHERE v.status IN ('SUCCESS', 'FAILED') "
            + "AND v.freeze_amount IS NOT NULL AND v.freeze_amount > 0 "
            + "AND NOT EXISTS (SELECT 1 FROM balance_transaction b "
            + "WHERE b.task_id = v.id AND b.type = CASE WHEN v.status = 'SUCCESS' THEN 'SETTLE' ELSE 'RELEASE' END) "
            + "ORDER BY v.id ASC LIMIT #{limit}")
    List<VideoTask> findTerminalMissingWalletTransition(@Param("limit") int limit);
}
