-- 在途并发额度：企业客户买的是「同时能跑几路」，不是「每分钟能提交几次」。
-- 一条生成任务活好几分钟，令牌桶那个量纲对生成类 API 根本对不上；而并发数自带时钟——
-- 下游慢下来时在途数自然顶住，提交速率跟着降，这才是共享队列的公平性本身。
--
-- 三列全部 NULL 默认，NULL = 不限 = 现状。所以这次迁移落地不改变任何账号的行为。

ALTER TABLE app_user
    ADD COLUMN account_tier         VARCHAR(32) NULL COMMENT '档位名（STANDARD/TEAM/ENTERPRISE）；数值在配置里，NULL=不限',
    ADD COLUMN concurrency_override INT         NULL COMMENT '单客户特谈的在途上限，优先于档位；NULL=按档位，0=禁止提交';

-- 档位名进库、数值进配置：调整「企业版=50 路」是改 yaml 重启，不是 UPDATE 一万行，
-- 而且能灰度、能回滚。但一定会有单独谈的客户，所以留 override 这个逃生口。
--
-- 这两列只有管理员能改。key 的创建权已经下放给用户了（D-030），
-- 档位如果也能自选，那就是自助提权。

ALTER TABLE api_key
    ADD COLUMN max_concurrency INT NULL COMMENT '本把 key 的在途上限；生效值取 min(账号上限, 本值)，只能收紧不能放宽';

-- key 级上限「只能往小调」是硬约束——只要保证这一点，这个开关就能安全地放给用户自己设。
-- 企业的真实需求：「测试环境那把 key 限 2 路，别让实习生的脚本吃光生产额度」。

-- 对账每 2 秒查一次「谁还在跑」，用的就是这条：SELECT id, user_id WHERE status='PROCESSING'。
-- 把 id 也放进索引 → 覆盖索引，不用回表。
-- 现有的 idx_video_task_status 是单列的，回表成本随历史任务量增长。
CREATE INDEX idx_vt_status_user ON video_task (status, user_id, id);
