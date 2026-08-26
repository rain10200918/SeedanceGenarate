package org.example.seedancegenarate.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.example.seedancegenarate.entity.Canvas;

@Mapper
public interface CanvasMapper extends BaseMapper<Canvas> {

    /**
     * 增量保存的乐观并发门：版本号相符才推进，返回 0 表示期间已有别的保存落地（冲突）。
     * 用版本号而非 update_time —— DATETIME 秒精度下同一秒内的两次保存会漏判冲突、静默丢失更新。
     */
    @Update("UPDATE canvas SET version = version + 1, last_mutation_id = #{mutationId}, "
            + "viewport = COALESCE(#{viewport}, viewport) "
            + "WHERE id = #{id} AND version = #{baseVersion}")
    int bumpVersion(@Param("id") Long id,
                    @Param("baseVersion") long baseVersion,
                    @Param("mutationId") String mutationId,
                    @Param("viewport") String viewport);
}
