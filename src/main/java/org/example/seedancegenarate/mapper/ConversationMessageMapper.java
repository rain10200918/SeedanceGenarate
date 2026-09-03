package org.example.seedancegenarate.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import lombok.Data;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.example.seedancegenarate.entity.ConversationMessage;

import java.util.Collection;
import java.util.List;

@Mapper
public interface ConversationMessageMapper extends BaseMapper<ConversationMessage> {

    /** 终态回填：按 task_id 全表匹配（biz_task_id 全局唯一）；重复事件幂等 */
    @Update("UPDATE conversation_message SET gen_status = #{status}, output = #{output}, error_msg = #{errorMsg} "
            + "WHERE task_id = #{taskId} AND kind = 'GENERATION'")
    int applyTaskResult(@Param("taskId") String taskId, @Param("status") String status,
                        @Param("output") String output, @Param("errorMsg") String errorMsg);

    /**
     * 左栏缩略图：每个对话最近一次成功的生成卡片，output / gen_params 原样带回，封面怎么取由 service 决定。
     * ids 不能为空——IN () 是语法错，调用方先判。
     */
    @Select("<script>SELECT m.conversation_id AS conversationId, m.output AS output, m.gen_params AS genParams "
            + "FROM conversation_message m JOIN ("
            + "SELECT conversation_id, MAX(id) AS id FROM conversation_message "
            + "WHERE kind = 'GENERATION' AND gen_status = 'SUCCESS' AND output IS NOT NULL "
            + "AND conversation_id IN <foreach item='id' collection='ids' open='(' separator=',' close=')'>#{id}</foreach> "
            + "GROUP BY conversation_id) t ON t.id = m.id</script>")
    List<LatestOutput> latestSuccessOutputs(@Param("ids") Collection<Long> ids);

    @Data
    class LatestOutput {
        private Long conversationId;
        private String output;
        private String genParams;
    }
}
