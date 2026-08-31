-- API Key 自助创建：记录签发来源，出事时能回答「谁、什么时候、从哪建的」。
-- 下放创建权之后这条审计线是必需的：明文只展示一次，一旦泄漏只能靠这两列回溯。
ALTER TABLE api_key
    ADD COLUMN created_by BIGINT NULL COMMENT '签发者：自助=本人 user_id，管理员代建=管理员 user_id',
    ADD COLUMN created_ip VARCHAR(64) NULL COMMENT '签发来源 IP';

-- 历史 key 全部由管理员在后台创建，属主即申请人，签发者无从追溯，留 NULL 表示「未知/历史」。
