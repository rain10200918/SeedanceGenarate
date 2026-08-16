-- 公告正文支持 base64 插图：TEXT(64KB) 放不下 5MB 图 base64(≈6.7MB)，改 LONGTEXT。
-- 公告量小，LONGTEXT 无实际压力；与前端 base64LimitSize=5MB 配套。

ALTER TABLE announcement MODIFY COLUMN content LONGTEXT NOT NULL COMMENT '公告正文（富文本 HTML，可含 base64 图）';
