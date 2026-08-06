package org.example.seedancegenarate.dto;

import lombok.Data;

/**
 * 修改密码请求（PUT /api/user/password）。
 */
@Data
public class ChangePasswordRequest {
    private String oldPassword;
    private String newPassword;
}
