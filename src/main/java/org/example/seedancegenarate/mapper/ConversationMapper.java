package org.example.seedancegenarate.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.example.seedancegenarate.entity.Conversation;

@Mapper
public interface ConversationMapper extends BaseMapper<Conversation> {

    /** 发消息前锁住对话行：seq 槽位只能在这把锁里分配，两个标签页同时发也不会撞唯一键 */
    @Select("SELECT * FROM conversation WHERE id = #{id} AND user_id = #{userId} FOR UPDATE")
    Conversation lockForOwner(@Param("id") Long id, @Param("userId") Long userId);

    /** 一次预留整轮的 seq：返回值是本轮第一个 seq 之前的旧上限 */
    @Update("UPDATE conversation SET message_count = message_count + #{slots}, last_message_at = NOW() WHERE id = #{id}")
    int reserveSeq(@Param("id") Long id, @Param("slots") int slots);

    @Update("UPDATE conversation SET title = #{title} WHERE id = #{id} AND title_source = 'AUTO'")
    int setAutoTitle(@Param("id") Long id, @Param("title") String title);
}
