-- 公告板：管理端发布，所有用户可见。
-- 用户端读「已发布」公告走进程快照（复用配置失效广播，发布后秒级全网可见）。
-- v1 无定时发布/定向，需要时加字段即可。

CREATE TABLE announcement (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    title VARCHAR(200) NOT NULL COMMENT '公告标题',
    content TEXT NOT NULL COMMENT '公告正文（纯文本，支持换行）',
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT 草稿 / PUBLISHED 已发布 / OFFLINE 已下线',
    create_by BIGINT NULL COMMENT '发布管理员 id',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    KEY idx_ann_status_time (status, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='公告板';
