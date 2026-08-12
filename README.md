# SeedanceGenarate — 多提供方 AI 视频 / 图片生成后端

> 一个把「文字 / 图片 → 视频 / 图片」的生成能力抽象成**可插拔提供方**的 Spring Boot 后端。
> 通过两层「策略 + 注册表」设计，把云端 API（Seedance）与自建 GPU 集群（ComfyUI 多实例）统一到同一套任务生命周期、计费、鉴权与开放能力之下，并附带一套对外售卖用的 API 服务。

---

## 目录

- [项目简介](#项目简介)
- [核心亮点](#核心亮点)
- [技术栈](#技术栈)
- [整体架构](#整体架构)
- [功能特性](#功能特性)
- [对外 API 服务](#对外-api-服务)
- [目录结构](#目录结构)
- [快速开始](#快速开始)
- [关键配置](#关键配置)
- [测试](#测试)
- [已知事项与演进](#已知事项与演进)
- [相关文档](#相关文档)

---

## 项目简介

**SeedanceGenarate** 是一个全栈项目（个人开发，前后端分离）：

- **后端（本仓库）**：Spring Boot 3 服务，统一承接「文本 / 图片 → 视频 / 图片」的生成请求，把生成能力抽象成可插拔的 provider：
  - **Seedance**：火山方舟（Volcano Ark）云端 API，按秒计费。
  - **ComfyUI**：自建多实例 GPU 集群（同主机多端口、共享一套模型），按成功计费。
- **前端**（配对仓库）：Vue3 + Pinia + Element Plus，由 `/api/video/options` 接口驱动「提供方 / 模型 / 比例 / 时长」选择器，**加新模型前端无需改代码**。

配套能力包括：注册 / 登录（token 鉴权）、邀请码、按次 / 按秒计费、令牌桶限流、阿里云 OSS 参考图存储、提示词优化（后端代理大模型，密钥不下发前端）、SSE 实时状态推送，以及一套面向外部开发者的 **API 售卖层**（`sk-` 钥匙、HMAC 签名 webhook、幂等提交、两阶段调用日志）。

---

## 核心亮点

1. **两层「策略 + 注册表」抽象，扩展成本极低**
   - 第一层 **提供方**：`VideoEngine` 接口 + `VideoEngineRegistry`，新增一个提供方 = 新增一个 `@Component implements VideoEngine`，其余零改动。
   - 第二层 **ComfyUI 模型**：`ComfyUiEngine` 内部再持有 `Map<model, WorkflowBuilder>`，每个模型一份 `WorkflowBuilder`（把 prompt / 图片 / 时长 / 比例注入工作流 JSON），新增一个模型 = 新增一个类 + 一份模板 JSON。
   - 前端 `/options` 遍历注册表下发 `ModelSpec` 能力约束（输出类型 / 时长档位 / 比例 / 图数量 / 分辨率），驱动选择器渲染——**加模型、改能力，前端零代码**。

2. **单一路径、单一事实源**
   - 所有生成任务共用 `video_task` 一张表 + `biz_task_id` / `provider_task_id` / `provider` / `node_id` / `model` 判别列，不为 ComfyUI / 对外 API 另开并行子系统，历史记录不割裂；旧 `task_id` 在过渡期继续兼容。
   - UI 与对外 API **共用 `VideoSubmitService` 提交编排**（模型解析 → 开放闸门 → 落库 → 引擎提交 → 计费），两条入口行为天然一致。

3. **计费时机由引擎声明，幂等记账**
   - `VideoEngine.billingTiming()`：Seedance = `ON_SUBMIT`（云端按秒预扣），ComfyUI = `ON_SUCCESS`（自建按结果结）。
   - `cost_record.task_id` 由唯一索引做最终幂等兜底，重复终态处理不会重复扣费；用户累计消费使用数据库原子累加。

4. **前端不轮询，改为服务端驱动 + SSE 推送**
   - 后台 `VideoTaskPoller` 持续推进 `PROCESSING` 任务，完成即将远端产物流式转存到 OSS（规避云端地址过期和实例本地磁盘依赖）；终态经 `@TransactionalEventListener(AFTER_COMMIT)` 发事件 → SSE 推给对应浏览器。
   - SSE 尽力而为、非权威，**DB 仍是唯一真相**；断线由前端 `EventSource` 自动重连 + refetch 兜底。

5. **对外 API 的工程化细节**
   - API Key 只存 SHA-256 哈希 + 明文仅创建时返回一次；webhook 带 HMAC-SHA256 签名防伪造，`(task_id, status)` 唯一索引保证幂等，退避重试 3 次。
   - 提交幂等（`Idempotency-Key` / `request_id`）、两阶段调用日志（RECEIVED → 终态）、按 key 令牌桶限流（429 带 `Retry-After`）、统一 `{error:{code,message,request_id}}` 错误契约。

6. **面向多实例的分布式能力（Redis + 持久化作业）**
   - **Redis Lua 分布式限流**：`feature.redis-rate-limit` 开启后全局限流额度一致，多实例不会放大配额。
   - **登录 Token 存 Redis**：Hash 保存 userId + 有效期，TTL 低于阈值自动续期；MySQL 不再保存登录态。
   - **跨实例 SSE**：`feature.redis-task-events` 开启后终态经 Redis Pub/Sub 广播，所有 API 实例都能推给自己的 SSE 连接。
   - **全局定时任务锁**：`distributed.lock.enabled` 开启后 Poller / Webhook / 对账同一时刻只在一个实例执行。
   - **持久化作业（async_job）**：流水线节点提交、任务终态收尾都变成 MySQL 作业表 + 行级租约，多 Worker 并行领取、崩溃自动接管；作业可用经 Redis 通知即时唤醒消费（无忙等轮询）。
   - **事件驱动完成通知**：ComfyUI 提交时注入 webhook_url（完成后主动回调），Seedance 按 `next_poll_at` 退避轮询，对账任务低频兜底防丢。
   - **ETA 预计完成时间**：ComfyUI 直接查真实队列给出排队位置（`GET /api/video/task/{id}/eta`），平均耗时按 model 统计并 Redis 共享缓存，前端详情页展示进度与预计剩余。

---

## 技术栈

| 层 | 技术 |
|---|---|
| 语言 / 框架 | Java 17 · Spring Boot 3.3.5 · Spring Web / AOP |
| 持久层 | MyBatis-Plus 3.5.7 · MySQL · Flyway（版本化数据库迁移） |
| 缓存 / 协调 | Redis（Lua 限流 · Token · Pub/Sub · 分布式锁 · ETA 统计缓存） |
| 引擎通信 | Hutool 5.8.27（ComfyUI HTTP）· Jackson（工作流 JSON 编辑）· Aliyun OSS SDK 3.17.4 |
| 其他 | Lombok · ip2region（IP 属地，离线 xdb）· spring-security-crypto |
| 前端（配对仓库） | Vue3 · Pinia · Element Plus · axios · SSE (`EventSource`) |

---

## 整体架构

### 系统总览

```mermaid
flowchart TB
    subgraph CLI["客户端"]
        FE["Vue3 前端<br/>Pinia + Element Plus + axios"]
        DEV["外部开发者<br/>持 sk- API Key"]
    end

    subgraph BE["Spring Boot 后端（API / Worker 一体，可多实例）"]
        IN["拦截器链<br/>Auth(Redis Token) / ApiKey / RateLimit(Redis Lua)"]
        CTRL["Controller 层<br/>Auth / Video / ApiVideo / Admin / TaskCallback"]
        SUB["VideoSubmitService<br/>UI 与 API 共用提交编排"]
        REG["VideoEngineRegistry<br/>Map&lt;provider, VideoEngine&gt;<br/>+ 能力声明：回调机制 / ETA"]
        SE["SeedanceEngine<br/>POLL + BASIC"]
        CE["ComfyUiEngine<br/>CALLBACK + FULL"]
        WB["WorkflowBuilder 策略集<br/>MiniMaxH3 / ZImageTurbo / ..."]
        GATE["ModelAccessService<br/>模型开放闸门"]
        ETA["TaskEtaService<br/>排队位置 + 平均耗时"]
        POLL["VideoTaskPoller<br/>退避轮询（仅 POLL 引擎）"]
        RECON["TaskReconcileTask<br/>低频兜底对账"]
        CON1["TaskFinalizeConsumer<br/>终态收尾（下载 → OSS）"]
        CON2["PipelineNodeSubmitConsumer<br/>流水线节点提交"]
        SSE["TaskStreamManager<br/>本地 SSE 连接"]
        WH["WebhookDispatcher<br/>HMAC 回调 + 重试"]
    end

    subgraph REDIS["Redis"]
        RL["限流令牌桶（Lua）"]
        TK["登录 Token（Hash + TTL）"]
        PS["Pub/Sub：task-status / job-available"]
        LK["分布式锁"]
        ETAC["ETA 统计缓存"]
    end

    subgraph STORE["存储"]
        DB[("MySQL<br/>video_task / async_job / cost_record<br/>api_key / model_access")]
        OSS[("阿里云 OSS<br/>参考图 + 生成产物")]
    end

    subgraph PROV["提供方"]
        SD["Seedance 云端 API<br/>火山方舟"]
        NG["nginx（X-Comfy-Token 校验）"]
        CF["ComfyUI 多实例<br/>gpu-0/1/3/6 · 共享模型"]
    end

    FE -->|"HTTP + SSE ?token="| IN
    DEV -->|"POST /api/v1/** · Bearer sk-"| IN
    IN --> CTRL
    CTRL --> SUB
    SUB --> GATE
    SUB --> BILL
    SUB --> REG
    REG --> SE
    REG --> CE
    CE --> WB
    SE --> SD
    CE --> NG
    NG --> CF
    CE -->|"下载/上传参考图"| OSS
    POLL --> REG
    POLL --> DB
    CON1 --> DB
    CON2 --> DB
    CON1 --> OSS
    ETA --> REG
    CTRL --> ETA
    SUB --> DB
    SUB --> REDIS
    POLL --> REDIS
    CON1 --> REDIS
    RECON --> DB
    RECON --> REDIS
    SSE -.->|"终态推送"| FE
    WH -.->|"异步回调"| DEV
```

### 核心设计：两层「策略 + 注册表」

```mermaid
flowchart TD
    OPT["GET /api/video/options<br/>遍历注册表下发 ModelSpec<br/>（输出类型 / 时长 / 比例 / 图数 / 分辨率）"] --> REG

    subgraph L1["第一层 · 提供方（VideoEngine）"]
        REG["VideoEngineRegistry<br/>Map&lt;provider, VideoEngine&gt;"]
        SE["SeedanceEngine<br/>云端 · ON_SUBMIT<br/>POLL轮询 · BASIC估算"]
        CE["ComfyUiEngine<br/>自建 · ON_SUCCESS<br/>CALLBACK回调 · FULL队列"]
        REG --> SE
        REG --> CE
    end

    subgraph L2["第二层 · ComfyUI 模型（WorkflowBuilder）"]
        CE --> WM["Map&lt;model, WorkflowBuilder&gt;"]
        WM --> M1["MiniMaxH3WorkflowBuilder<br/>参考生视频"]
        WM --> M2["MiniMaxH3TextToVideoWorkflowBuilder<br/>文生视频"]
        WM --> M3["MiniMaxH3AccelWorkflowBuilder<br/>参考生视频 · 官方加速"]
        WM --> M4["ZImageTurboWorkflowBuilder<br/>文生图（输出 PNG）"]
        WM --> M5["QwenImageEditWorkflowBuilder<br/>图生图（≤3 张参考图）"]
    end

    SD[("火山方舟 API")]
    CF["ComfyUI 实例池<br/>least-queue / round-robin 选节点"]
    SE --> SD
    CE --> CF

    style L1 fill:#eef4ff,stroke:#6b8ef0
    style L2 fill:#eefaf1,stroke:#4fbf6a
```

> 扩展方式：新增一个提供方 = 加一个 `VideoEngine` 实现；新增一个 ComfyUI 模型 = 加一个 `WorkflowBuilder` + 一份模板 JSON。注册表会自动把它暴露到 `/options`。

> **能力声明（策略驱动框架分流）**：`VideoEngine` 除业务方法外声明三类能力——`completionMechanism()`（CALLBACK 事件驱动 / POLL 轮询）、`etaCapability()`（FULL 可查真实队列 / BASIC 时间估算）、`needsPolling()`（未配置回调时回退轮询）。框架按声明统一分流：poller 只查需要轮询的引擎、ETA 按能力组装、回调端点按提供方路由。新增引擎零改动即可接入。

### 分布式设计（Redis + 持久化作业）

```mermaid
flowchart LR
    subgraph API["API 实例（可多台，无状态）"]
        A1["实例 A"]
        A2["实例 B"]
    end

    subgraph REDIS["Redis"]
        R1["限流 Lua<br/>全局配额一致"]
        R2["Token Hash<br/>跨实例登录态"]
        R3["Pub/Sub<br/>task-status / job-available"]
        R4["分布式锁<br/>全局任务单实例执行"]
        R5["ETA 统计<br/>avg / queue 缓存"]
    end

    subgraph WORKER["Worker 逻辑（多实例并行，行级租约）"]
        W1["TaskFinalizeConsumer<br/>终态收尾并行"]
        W2["PipelineNodeSubmitConsumer<br/>流水线节点提交"]
    end

    subgraph DB["MySQL"]
        D1["video_task<br/>任务状态真相"]
        D2["async_job<br/>持久化作业 + 租约"]
    end

    A1 --> R1
    A2 --> R1
    A1 --> R2
    A2 --> R2
    A1 --> R3
    A2 --> R3
    W1 --> R4
    W1 --> D2
    W2 --> D2
    W1 --> D1
    A1 --> D1
    A2 --> D1

    style REDIS fill:#fff3e0,stroke:#f5a623
    style WORKER fill:#eefaf1,stroke:#4fbf6a
```

**关键原则**：

- **MySQL 是业务状态唯一真相**，Redis 只做限流 / 登录态 / 通知 / 锁 / 可重建缓存；
- **作业化**：提交、终态收尾、流水线节点都走 `async_job` 行级租约——多 Worker 并行领取、崩溃自动接管、Redis 通知即时唤醒（空闲 30 秒兜底扫描）；
- **事件驱动完成**：ComfyUI 完成回调（秒级），Seedance 退避轮询（2s/5s/30s），对账任务低频兜底防丢；
- **能力声明分流**：poller 只查需要轮询的引擎，ETA 按引擎能力组装，新增引擎不改框架。

### 一次生成的任务生命周期

```mermaid
sequenceDiagram
    autonumber
    participant U as 前端 / 外部开发者
    participant C as Controller
    participant S as VideoSubmitService
    participant G as ModelAccessService
    participant E as VideoEngine
    participant B as Seedance / ComfyUI
    participant P as Poller / 回调 / 对账
    participant J as async_job 作业
    participant F as TaskFinalizeConsumer
    participant DB as MySQL
    participant R as Redis
    participant SSE as TaskStreamManager
    participant COST as CostRecordService

    U->>C: POST /text2video · /image2video · /api/v1/videos
    C->>S: submit(request)
    S->>G: 校验模型是否开放（effectiveModel 闸门）
    S->>DB: 落库 video_task（PROCESSING + provider/model/node）
    S->>E: submit(command)（CALLBACK 引擎附带 webhook_url）
    E->>B: 选节点 → 上传参考图 → 构建工作流 → /prompt
    E-->>S: 回写 providerTaskId / nodeId
    S-->>C: 返回 taskId
    C-->>U: 200（UI）/ 202（API）

    par 完成通知（按引擎能力分流）
        B->>P: ComfyUI 完成 → webhook 回调（秒级）
    and
        loop Poller 退避轮询（仅 POLL 引擎，2s/5s/30s）
            P->>DB: 查到期任务（next_poll_at）
            P->>B: poll(task)
            P->>DB: updateStatus + 更新 next_poll_at
        end
    and
        P->>DB: 对账兜底（60s 低频，回调/轮询丢失时）
    end

    alt 到达 SUCCESS
        P->>J: 入队 TASK_FINALIZE（Redis 通知唤醒）
        J->>F: Worker 领取（行级租约）
        F->>B: 下载产物（带 X-Comfy-Token）
        F->>DB: CAS PROCESSING→SUCCESS + artifact 元数据
        F-->>COST: recordOnSuccess（幂等）
        F->>R: 刷新 ETA 平均耗时 + Pub/Sub 终态
        R->>SSE: 跨实例广播
        SSE-->>U: SSE 推送 {taskId, status} → 前端展示结果
    else 到达 FAILED
        P->>DB: 落 FAILED + errorMsg
        P->>R: Pub/Sub 终态
        R->>SSE: 跨实例广播
        SSE-->>U: SSE 推送 {taskId, status}
    end
```

---

## 功能特性

**生成能力（当前 6 个模型）**

| 提供方 | 模型 | 能力 |
|---|---|---|
| Seedance | `seedance` / `seedance-fast` / `seedance-mini` | 文生视频 / 图生视频（云端，按秒计费） |
| ComfyUI | `minimax-h3` | 参考生视频（自建，按次计费） |
| ComfyUI | `minimax-h3-t2v` | 文生视频 |
| ComfyUI | `minimax-h3-accel` | 参考生视频 · 官方加速（可选 megapixels 分辨率档位） |
| ComfyUI | `z-image-turbo` | **文生图**（输出 PNG） |
| ComfyUI | `qwen-image-edit` | **图生图**（≤3 张参考图） |

**配套能力**

- 注册 / 登录（token 存 Redis，TTL 续期）+ 邀请码体系 + 按 IP 限流；管理员后台（用户角色、模型开关、API Key）。
- 计费：Seedance 按秒单价 × 时长、ComfyUI 一口价；记账幂等，按 `task.id` 去重。
- **模型开放闸门**：`ModelAccessService` 为唯一权威，提交时按「实际生效模型」硬校验（防手拼请求绕过），管理员可运行时开 / 关模型。
- **提示词优化**：后端代理 LLM，系统提示词按模型选模板（`resources/prompts/{model}.md`，可零代码新增风格），LLM Key 仅后端持有。
- **SSE 实时状态**：`GET /api/video/stream` 替代前端轮询；`GET /task/{id}` 纯读库兜底；多实例经 Redis Pub/Sub 跨实例推送。
- **ETA 预计完成时间**：`GET /api/video/task/{id}/eta` 返回排队位置 / 进度 / 预计剩余（ComfyUI 查真实队列，平均耗时按 model 统计 + Redis 共享缓存）；前端详情页展示。
- **事件驱动完成通知**：ComfyUI webhook 回调（秒级）+ Seedance 退避轮询 + 对账兜底；作业消费经 Redis 通知唤醒（空闲 30 秒兜底扫描，无忙等）。
- 产物（视频 / 图片）统一流式转存到阿里云 OSS，数据库保存 `artifact_key` 和媒体元数据；播放/下载接口鉴权后签发短期 OSS URL。历史 `data/videos/` 文件保留兼容读取，OSS Lifecycle 负责正式产物过期清理。
- **API 接入文档页**：`GET /api/video/api-docs`（登录用户可读）与对外 API 文档同一份 Markdown；前端「API 文档」页面渲染。

---

## 对外 API 服务

把生成能力以 API 方式对外售卖（薄接入层，全部复用现有引擎 / 计费 / 任务生命周期）：

- `POST /api/v1/videos` 提交（`Bearer sk-`，可选 `Idempotency-Key`，202 返回 taskId）
- `GET /api/v1/videos` / `/{taskId}` / `/{taskId}/content` 列表 / 查询 / 下载
- `GET /api/v1/models` 模型清单（受模型开关过滤）
- webhook 终态回调（`X-Signature` HMAC 签名、`(task_id,status)` 幂等、退避重试）

设计要点：key 只存 SHA-256 哈希、`api_call_log` 两阶段日志为统计唯一真相（聚合现算不建计数器表）、四个幂等点（提交 / 计费 / webhook / 限流）。详见 [`API_SERVICE_DESIGN.md`](API_SERVICE_DESIGN.md)。

---

## 目录结构

```
src/main/java/org/example/seedancegenarate/
├── engine/               # 提供方层（核心抽象）
│   ├── VideoEngine       #   接口：provider / submit / poll / models / billingTiming
│   │                     #   + 能力声明：completionMechanism / etaCapability / needsPolling
│   ├── VideoEngineRegistry
│   ├── Impl/SeedanceEngine
│   ├── Impl/ComfyUiEngine
│   └── comfyui/          # ComfyUI 支撑：Client / NodeScheduler / WorkflowBuilder 策略集
│       └── Impl/         #   MiniMaxH3 / T2V / Accel / ZImageTurbo / QwenImageEdit
├── controller/           # Video / Auth / UserAdmin / InviteCode / TaskCallback / GlobalExceptionHandler
├── service/              # VideoSubmitService（UI/API 共享编排）· Pricing · OSS · PromptOptimize …
│   └── Impl/             #   TaskEtaServiceImpl · RedisDistributedLock · TokenCacheService …
├── interceptor/          # Auth + 限流 + ApiKey（对外 API）
├── config/               # 各 @ConfigurationProperties + WebConfig
├── event/                # 终态领域事件
├── stream/               # SSE 管理 + Redis Pub/Sub（TaskStatus / JobAvailable）
├── task/                 # 推进器 / 终态消费 / 流水线消费 / 对账 / webhook
├── exception/  dto/  entity/  mapper/  context/  util/
└── resources/
    ├── application.yaml  # 全部配置支持 ${ENV:默认值}
    ├── db/migration/     # Flyway 版本化数据库迁移（V1 基线 → V6 轮询退避）
    ├── schema.sql        # 历史参考：不再启动自动执行
    ├── comfyui/workflows/  # 工作流模板 JSON
    └── prompts/            # 提示词优化模板（{model}.md）
```

---

## 快速开始

```bash
# 1. 准备 MySQL（启动时由 Flyway 执行 db/migration/V1__baseline.sql；已有本地库会自动 baseline）
# 2. 配置：至少数据库连接 + 你实际使用的提供方密钥（见「关键配置」）

# 3. 编译
./mvnw clean compile

# 4. 单元测试（纯 JUnit，不依赖 Spring/DB）
./mvnw clean test
#    注：SeedanceGenarateApplicationTests 会启动 Spring 上下文并连 MySQL，
#    跑全量测试需 MySQL 可用；WorkflowBuilder*Test 无需任何外部依赖。

# 5. 启动（默认 :8080）
./mvnw spring-boot:run
```

启动后：

- UI 接口：`POST /api/auth/register` → `POST /api/auth/login` 拿 token → `POST /api/video/text2video`
- 能力清单：`GET /api/video/options`
- SSE 推送：`GET /api/video/stream?token=xxx`

---

## 关键配置

所有配置均为 `${ENV:默认值}` 形式，生产环境用环境变量覆盖（`application.yaml` 见 §11 详表）。数据库结构由 Flyway 管理，默认启用 `spring.flyway.enabled=true` 且 `spring.sql.init.mode=never`。

| 配置项 | 说明 |
|---|---|
| `SPRING_DATASOURCE_*` | MySQL 连接（Hikari 池大小按实例数预算） |
| `SPRING_REDIS_*` | Redis（Token / 限流 / Pub/Sub / 锁 / ETA 缓存；池大小按每请求 2 次 Redis 预算） |
| `SPRING_FLYWAY_*` / `SPRING_SQL_INIT_MODE` | Flyway 迁移开关 / 旧 SQL 初始化开关 |
| `SEEDANCE_API_KEY` / `SEEDANCE_MODEL*` | Seedance 密钥与模型 |
| `COMFYUI_NODE{0,1,3,6}_URL` / `_ENABLED` | ComfyUI 实例节点 |
| `COMFYUI_ACCESS_TOKEN` | ComfyUI 访问令牌（所有请求带 `X-Comfy-Token`，nginx 入口校验） |
| `VIDEO_CALLBACK_BASE_URL` / `_SECRET` | ComfyUI 完成回调地址与鉴权 token（未配置自动回退轮询） |
| `AUTH_TOKEN_*` | 登录 Token 有效期（idle / max lifetime / 续期阈值）与 Redis 前缀 |
| `FEATURE_REDIS_RATE_LIMIT` / `_TASK_EVENTS` | 分布式限流 / 跨实例 SSE 开关（多实例必须开） |
| `DISTRIBUTED_LOCK_*` | 全局定时任务锁（多实例必须开） |
| `ASYNC_JOB_*` | 持久化作业（租约 / 退避 / 兜底扫描间隔 / Redis 通知频道） |
| `TASK_STATUS_REDIS_CHANNEL` / `ASYNC_JOB_REDIS_CHANNEL` | Pub/Sub 频道（不同环境用不同前缀隔离） |
| `ALIYUN_OSS_*` | 参考图与生成产物对象存储（须后端可读，ComfyUI 会回源下载）；`ALIYUN_OSS_ARTIFACT_PREFIX` / `ALIYUN_OSS_SIGNED_URL_TTL_SECONDS` 控制产物前缀与签名有效期 |
| `PROMPT_OPTIMIZE_API_KEY` | 提示词优化 LLM 密钥（仅后端） |
| `BILLING_*` / `RATE_LIMIT_*` / `VIDEO_POLL_*` | 计费 / 限流 / 推进器参数 |
| `VIDEO_MODEL_ACCESS_DEFAULT_OPEN` | 新模型默认是否开放 |

> ⚠️ **安全**：仓库内 `application.yaml` 的默认值含真实样式的密钥，**仅供本地开发**；务必用环境变量覆盖真实密钥，切勿把真实密钥提交进仓库。

---

## 测试

- 单元测试 + 可选本地 Redis 集成测试（`RUN_REDIS_INTEGRATION_TESTS=true`），`./mvnw clean test` 全绿。
- 覆盖：5 个 `WorkflowBuilder`、`GenerationMode`、`ModelAccessService`、`PromptOptimizeService`、`VideoEngine.effectiveModel`（闸门修复回归）、Redis 限流 Lua、Token 缓存、分布式锁、作业入队/领取/重试、终态消费、回调鉴权、Pub/Sub 发布订阅等。
- 均为纯 JUnit，不依赖 Spring 上下文与数据库（`ApplicationTests` 除外）。

---

## 已知事项与演进

- **多实例部署**：核心链路已分布式化（Redis 限流 / Token / Pub/Sub SSE / 全局锁 / 持久化作业 / 事件驱动完成）。部署要点：开启 `FEATURE_REDIS_RATE_LIMIT`、`FEATURE_REDIS_TASK_EVENTS`、`DISTRIBUTED_LOCK_ENABLED`，各 Redis key/channel 使用环境前缀隔离，MySQL/Redis 连接池按实例数预算。
- **ComfyUI 访问安全**：建议 nginx 入口校验 `X-Comfy-Token`（header 或 `?token=`），ComfyUI 只监听本机；后端所有调用已统一携带 token。
- **任务推进**：ComfyUI 事件驱动（webhook 回调）+ 对账 60 秒兜底；Seedance 退避轮询（2s/5s/30s）；poller 只查需要轮询的引擎。
- **演进路线**：任务轮询行级租约（多 Worker 并行推进，PR-09）、流水线多次运行历史、Outbox 可靠事件、API/Worker 角色拆分、Actuator 指标与告警、更多模型（Wan / Hunyuan / LTX）。
- 实测发现并修复的典型问题（面试可展开）：模型开放闸门绕过（`effectiveModel` 默认实现）、Spring advice 排序吞掉 `ApiException`（`@Order`）、`Map.of` 的 null key NPE、分布式锁开关关闭导致任务不执行（锁未启用需回退直接执行）、对账查询 NULL next_poll_at 不匹配、scoped CSS 对 v-html 内容失效（`:deep()`）。

---

## 相关文档

- [`ARCHITECTURE.md`](ARCHITECTURE.md) —— 架构速览：两层策略、任务生命周期、计费、鉴权限流、数据模型、分布式改造。
- [`API_SERVICE_DESIGN.md`](API_SERVICE_DESIGN.md) —— 对外 API 业务设计与落地偏差。
- [`DISTRIBUTED_MIGRATION_PLAN.md`](DISTRIBUTED_MIGRATION_PLAN.md) —— 分布式改造阶段实施方案（PR 拆分）。
- `docs/architecture.mmd` —— 架构图独立文件，可用 [mermaid.live](https://mermaid.live) 渲染 / 导出 PNG 用于演示。
- `docs/OSS_URL_RECOVERY.md` —— OSS 裸域名 URL 线上修复指南。
- 配对前端仓库：`/Users/a1234/WebstormProjects/seedance_generate`（Vue3 + Pinia + Element Plus；`USER_GUIDE.md` 为用户使用手册）。
