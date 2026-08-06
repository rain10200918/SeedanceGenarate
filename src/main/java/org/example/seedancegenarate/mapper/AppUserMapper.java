package org.example.seedancegenarate.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.example.seedancegenarate.entity.AppUser;

@Mapper
public interface AppUserMapper extends BaseMapper<AppUser> {
}
