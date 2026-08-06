package org.example.seedancegenarate.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 模型开放状态（管理员运行时开关）。
 * <p>
 * 稀疏覆盖：只存管理员显式设过开关的模型；没有行的模型走默认（{@code video.model-access.default-open}）。
 * 「有哪些模型」仍以 {@code VideoEngineRegistry} 为准，本表只叠加开关，避免与注册表漂移。
 */
@Data
@TableName("model_access")
public class ModelAccess {
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 模型标识（ModelSpec.model），全局唯一 */
    private String model;
    /** 是否开放：true=对普通用户可见可用 */
    private Boolean enabled;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
