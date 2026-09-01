-- 新注册必须验证邮箱；存量账号保持 NULL，继续按用户名登录。
ALTER TABLE app_user
    ADD COLUMN email VARCHAR(254) NULL COMMENT '规范化邮箱；仅验证成功后写入' AFTER username,
    ADD COLUMN email_verified_at DATETIME NULL COMMENT '邮箱验证完成时间' AFTER email,
    ADD UNIQUE KEY uk_app_user_email (email);
