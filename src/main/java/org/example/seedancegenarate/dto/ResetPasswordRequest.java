package org.example.seedancegenarate.dto;

import lombok.Data;

/** 管理员重置用户密码请求体 */
@Data
public class ResetPasswordRequest {
    private String password;
}
