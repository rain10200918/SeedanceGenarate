-- 提示词优化用的 LLM 通道进库：换/加一个 OpenAI 兼容服务（自建 vLLM / DeepSeek / 中转）
-- 不再需要改 yaml + 重启。同一个模式第四次复用：表是事实、yaml 只做 seed、只归档不删除。
--
-- 和 comfy_node 唯一不一样的地方是这张表里有密钥。所以管理端读一律脱敏，
-- PATCH 传空等于保留原值，日志和异常消息里绝不出现（D-023）。

CREATE TABLE llm_channel
(
    name         VARCHAR(64)   NOT NULL COMMENT '通道名，如 default / deepseek-v3；prompt_token_usage.llm_channel 存的就是它',
    base_url     VARCHAR(255)  NOT NULL COMMENT '完整的 chat completions 地址',
    api_key      VARCHAR(255)  NOT NULL COMMENT 'Bearer 密钥。只在 LlmChatClient 里用；接口返回脱敏',
    model        VARCHAR(128)  NOT NULL COMMENT '对方服务那边的模型 ID',
    temperature  DECIMAL(3, 2) NULL COMMENT 'NULL = 不传。推理类模型不接受这个参数',
    max_tokens   INT           NOT NULL DEFAULT 1500 COMMENT 'H3 模板要完整英文六段，低于 1500 会截断',
    token_param  VARCHAR(32)   NOT NULL DEFAULT 'max_tokens' COMMENT 'max_tokens / max_completion_tokens / none —— 同是 OpenAI 兼容，这个字段名分了两派',
    timeout_ms   INT           NOT NULL DEFAULT 100000 COMMENT '读超时。必须小于前端 axios 的 120000，否则前端先断、后端白烧 token',
    priority     INT           NOT NULL DEFAULT 100 COMMENT '小的先用。路由只在快失败时切下一条，读超时直接失败',
    enabled      TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '参不参与路由。新增默认 0 —— 先试跑看输出质量再开',
    archived     TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '列表里显不显示。归档不删行，见下',
    remark       VARCHAR(255)  NULL COMMENT '给人看的：哪家、谁的账号、为什么关着',
    create_time  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (name)
) COMMENT '提示词优化 LLM 通道（OpenAI 兼容 chat completions）';

-- **没有 DELETE 接口，只有 archived。**
-- prompt_token_usage.llm_channel 记的是通道名，删了行之后「上个月那批失败是不是都在同一家」就查不了。
-- 归档拿到的是「列表里看不见、不再参与路由」这两个真实需求，代价只是一行留着。

-- 本次迁移**不灌数据**。yaml 里的地址和密钥是「美元花括号 PROMPT_OPTIMIZE_URL 冒号默认值」那种
-- 占位符，环境变量可覆盖；写死进 SQL 会把开发默认值灌进生产。seed 由启动时的 LlmChannelRegistry 做，
-- 读的是解析过 env 的 PromptOptimizeConfig，且只 INSERT 不 UPDATE。
-- （Flyway 的占位符替换对注释同样生效，迁移文件里任何位置都不许出现美元加花括号 —— V25 踩过。）

-- 每次调用记下是哪条通道服务的。切面按通道记，失败的那次也记，这样通道切换是事后完全可审计的。
ALTER TABLE prompt_token_usage
    ADD COLUMN llm_channel VARCHAR(64) NULL COMMENT '实际服务的通道名（llm_channel.name）；老行为 NULL' AFTER llm_model,
    ADD KEY idx_ptu_channel (llm_channel, create_time);
