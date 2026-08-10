package org.example.seedancegenarate.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.example.seedancegenarate.entity.UserToken;

/** 历史 user_token 表映射；新登录 Token 已改存 Redis。 */
@Mapper
public interface UserTokenMapper extends BaseMapper<UserToken> {
}
