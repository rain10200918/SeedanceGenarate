package org.example.seedancegenarate.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.example.seedancegenarate.entity.ConversationMessage;

@Mapper
public interface ConversationMessageMapper extends BaseMapper<ConversationMessage> {

    /** 终态回填：按 task_id 全表匹配（biz_task_id 全局唯一）；重复事件幂等 */
    @Update("UPDATE conversation_message SET gen_status = #{status}, output = #{output}, error_msg = #{errorMsg} "
            + "WHERE task_id = #{taskId} AND kind = 'GENERATION'")
    int applyTaskResult(@Param("taskId") String taskId, @Param("status") String status,
                        @Param("output") String output, @Param("errorMsg") String errorMsg);
}
