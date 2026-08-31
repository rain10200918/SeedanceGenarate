-- 算力节点清单进库：加一台 ComfyUI 不再需要改 yaml + 重启。
--
-- 这张表只存**人填的东西**（地址 / 开关 / 权重 / 备注）。
-- 死活、队列深度、装了哪些 node type、显存、版本 —— 那些是**观测态**，只活在
-- ComfyUiFleet 的内存快照里，绝不落库：3 秒探一轮 × N 台 = 一天十几万次写，
-- 而且多实例会互相覆盖。事实层和观测层永不互写（D-026 / D-034）。

CREATE TABLE comfy_node
(
    id          VARCHAR(64)  NOT NULL COMMENT '节点 ID，如 gpu-0；video_task.node_id 存的就是它',
    base_url    VARCHAR(255) NOT NULL COMMENT 'ComfyUI 基础地址',
    enabled     TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '派不派活。新增默认 0——新机器先验证再放量',
    archived    TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '列表里显不显示。归档不删行，见下',
    weight      DECIMAL(4, 2) NOT NULL DEFAULT 1.00 COMMENT '相对算力，H100=1.00，Spark(GB10)实测 0.45',
    remark      VARCHAR(255) NULL COMMENT '给人看的：这台在哪、谁维护、为什么关着',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
) COMMENT 'ComfyUI 算力节点清单（只存人填的，观测态在内存快照里）';

-- 主键用业务 id 而不是自增 BIGINT：video_task.node_id 里存的已经是 'gpu-0' 这种字符串，
-- 全部历史任务都是。换自增要一次数据迁移加一层映射，纯亏；而且 id 人念得出来，排障值钱。

-- **没有 DELETE 接口，只有 archived。**
-- ComfyUiEngine.poll() 靠 findNode(task.node_id) 找回处理该任务的机器，
-- 查不到直接 RemoteStatus.failed("找不到处理该任务的 ComfyUI 节点")——那是终态，不是重试。
-- 删掉一台正在跑 3 个 minimax 的机器 = 3 个任务当场判死 + 3 笔退款 + 约 60 分钟 H100 机时白烧，
-- 而 GPU 上那 3 个 prompt 还会继续跑到完（成了孤儿）。
-- 历史任务的 node_id 同样会悬空，事后归因（"上个月那批失败是不是都在同一台上"）从此查不了。
-- archived 拿到的是「列表里看不见」这个唯一真实需求，代价只是几百字节的行留着。

-- 本次迁移**不灌数据**。yaml 里的地址写成「美元花括号 COMFYUI_NODE0_URL 冒号默认值」那种
-- 占位符形式，环境变量可覆盖；写死进 SQL 的话，生产用了别的 env 就会灌进错地址。
-- seed 由启动时的 ComfyNodeRegistry 做，读的是已经解析过 env 的 properties，
-- 且**只 INSERT 不 UPDATE**——管理端改过的地址，重启不会被 yaml 覆盖回去。
--
-- 注意：这段话本来是直接写占位符原文的，结果 Flyway 启动即挂
-- （Unable to parse statement ... No value provided for placeholder）。
-- **Flyway 的占位符替换对注释同样生效**，迁移文件里任何位置都不许出现美元加花括号。
