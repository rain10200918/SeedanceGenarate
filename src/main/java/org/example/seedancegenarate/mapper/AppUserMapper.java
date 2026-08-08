package org.example.seedancegenarate.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.example.seedancegenarate.entity.AppUser;

import java.math.BigDecimal;

@Mapper
public interface AppUserMapper extends BaseMapper<AppUser> {

    @Update("UPDATE app_user SET total_cost = total_cost + #{amount} WHERE id = #{userId}")
    int incrementTotalCost(@Param("userId") Long userId, @Param("amount") BigDecimal amount);
}
