# 对外 API 服务 — 业务设计与架构

> 面向「把生成能力对外售卖/提供服务」的设计文档。**v1 已于 2026-08-05 实现**(后端 31 单测 + 前端 type-check 全绿),实现偏差见 §16。
> 关联:架构总览见 `ARCHITECTURE.md`;本文沿用其所有不变量(单一 `video_task` 生命周期、注册表为模型唯一真相、单一事实源、密钥仅后端持有)。

## 1. 业务定位

把现有的「文本/图片 → 视频/图片」生成能力,以 API 方式对外提供:外部开发者持一把 `sk-` 钥匙,调 `POST /api/v1/videos` 提交生成,按调用计费,结果异步交付(轮询或 webhook)。

**核心不变式:API 服务是现有系统的薄接入层**——引擎、模型、计费、任务生命周期、模型开关全部复用,不复制、不新建第二套。

## 2. 角色与用例

| 角色 | 做什么 |
|---|---|
| 管理员 | 创建/撤销 API Key、查看每把钥匙的调用统计(按模型/拒绝/消费)、沿用「模型开关」控制 API 可用模型 |
| 外部开发者 | 提交生成任务 → 轮询/收 webhook → 下载产物 |
| 属主用户(key 绑定账号) | 任务记在自己名下,后台可见 API 调用记录 |

## 3. 总体架构(新增 vs 复用)

```
外部客户端
   │  Bearer sk-xxx
   ▼
┌─ 接入层(新增) ───────────────────────────────────┐
│  /api/v1/**  ApiKeyInterceptor(鉴权 + 按 key 限流) │
│  ApiVideoController(提交/查询/列表/下载)            │
└──────────────────────────────────────────────────┘
   │ 注入 UserContext(属主用户)→ 下游零感知
   ▼
┌─ 业务层(复用) ──────────────────────────────────┐
│  VideoSubmitService(提取自 VideoController,共享)  │
│  → VideoEngineRegistry → effectiveModel → 闸门   │
│  → videoTaskService → engine.submit / poll        │
└──────────────────────────────────────────────────┘
   │ 终态 TaskStatusChangedEvent(AFTER_COMMIT)
   ▼
┌─ 事件层(新增一个监听器) ──────────────────────────┐
│  WebhookDispatcher(幂等重试投递)                   │
│  现有 TaskStreamManager(SSE,UI 用)不动             │
└──────────────────────────────────────────────────┘
```

**新增组件仅 5 个**:`ApiKeyInterceptor`、`ApiVideoController`、`ApiVideoService`、`WebhookDispatcher`、管理端 Key 页。

## 4. 核心业务流程(两平面)

**提交平面(同步,<1s 返回)**:

```
POST /api/v1/videos  {prompt, model, images?[url], duration?, ratio?, megapixels?}
  → 鉴权(key 哈希比对)→ 限流(按 key 令牌桶,429 带 Retry-After)
  → 校验:参数 / 模型存在 / 模型开放(复用 ModelAccessService)
  → 幂等检查:Idempotency-Key 已存在?→ 返回原 task_id(不重复扣费)
  → 落 video_task(PROCESSING, 带 api_key_id 判别列)
  → engine.submit(图片 URL → 下载 → OSS → 复用现有链路)
  → 202 {taskId, status:"PROCESSING", requestId}   ← 响应体是驼峰;request_id 是库表列名,别混
```

**交付平面(异步,分钟级)**:

```
VideoTaskPoller(现有)→ updateStatus 幂等落库
  → 更新 api_call_log(终态 + 耗时 + 金额)
  → 事件 → WebhookDispatcher:回调 callback_url(HMAC 签名,重试)
客户端:GET /api/v1/videos/{task_id} 轮询 / GET .../content 下载
```

**失败两平面**(不混):
- 接单失败 → 同步 4xx/5xx + `error.code`;
- 生成失败 → 轮询响应 `{status:"FAILED", error:{...}}`,不进提交响应的错误通道。

## 5. 错误契约

统一结构(所有失败):

```json
{ "error": { "code": "MODEL_NOT_FOUND", "message": "模型不存在", "requestId": "req_8f3a..." } }
```

| 场景 | HTTP | code | 可重试 |
|---|---|---|---|
| prompt 空 / 参数非法 | 400 | VALIDATION_ERROR | ❌ |
| 模型不存在 / 比例不支持 / 图片数不符 | 400 | MODEL_NOT_FOUND / RATIO_NOT_SUPPORTED / IMAGE_COUNT_INVALID | ❌ |
| 模型未开放 | 403 | MODEL_NOT_OPEN | ❌ |
| key 无效 | 401 | INVALID_API_KEY | ❌ |
| key 禁用 / 过期 | 403 | API_KEY_DISABLED / API_KEY_EXPIRED | ❌ |
| 限流 | 429 | RATE_LIMITED(带 Retry-After 头) | ✅ 等 Retry-After |
| 配额超限(v2) | 429 | QUOTA_EXCEEDED | ❌ |
| 产物已过保留期(30 天) | 410 | ARTIFACT_EXPIRED | ❌ 需重新生成 |
| 提供方不可用 | 503 | PROVIDER_UNAVAILABLE | ✅ |
| 内部错误 | 500 | INTERNAL_ERROR | ✅ |

提交成功 = **202**(异步):`{taskId, status:"PROCESSING", requestId}`(驼峰;项目未配 snake_case 策略)。

**产物保留期**:OSS 侧生命周期规则 `outputs-expire` 30 天删除,应用侧由
`video.artifact-retention-days` 推导(基准 create_time,见 D-029)。任务响应带派生字段
`artifactExpired`;过期后 `/content` 返回 410,任务记录本身不删。
**改 OSS 那边的天数必须同步改这个配置**——两个数在启动配置指纹里对照。

## 6. 鉴权与安全

- **Key 生命周期**:管理员创建 → 明文只展示一次(`sk-` 前缀)→ 库里只存 SHA-256 哈希;可禁用/设过期;
- **拦截器**:`Authorization: Bearer sk-xxx` → 查哈希 → 校验 status/expires_at → **把属主用户塞进 `UserContext`** → 下游 tasks 隔离/计费/活动记录全复用;
- 调用记录**不记 Authorization 全文**,只记 `key_prefix` + `api_key_id`;
- webhook 回调带 `X-Signature: HMAC-SHA256(secret, payload)`(防伪造回调);
- v2:同 key 高频 401/429 → 自动禁用。

## 7. 计费与限额

| 维度 | 设计 |
|---|---|
| 单价 | 复用 `PricingService`(Seedance 秒价×时长、ComfyUI 一口价),API 与 UI 同价 |
| 记账 | 复用 `CostRecordService`,记到属主账上;`api_call_log.cost_amount` 冗余一份对账 |
| 限流 | 按 key 令牌桶(单实例内存,多实例换 Redis);429 带 `Retry-After` |
| 配额(v2) | 按 key 月度额度,`SUM(cost_amount)` 超限拒绝 |
| 模型开关 | 复用 `model_access`——关掉的模型 API 也调不了(403 MODEL_NOT_OPEN) |

## 8. 数据模型(3 新表 + 1 扩展)

```sql
-- ① 钥匙属性
CREATE TABLE IF NOT EXISTS api_key (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,               -- 属主用户
  name VARCHAR(64),                      -- 用途备注
  key_prefix VARCHAR(16) NOT NULL,       -- sk- 前 8 位,后台展示用
  key_hash CHAR(64) NOT NULL UNIQUE,     -- SHA-256,不存明文
  status VARCHAR(16) NOT NULL DEFAULT 'ENABLED',   -- ENABLED / DISABLED
  expires_at DATETIME NULL,
  callback_url VARCHAR(512),             -- webhook 地址
  webhook_secret VARCHAR(128),           -- 回调签名密钥
  last_used_at DATETIME NULL,
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX idx_api_key_user (user_id)
);

-- ② 调用明细(唯一真相,统计全从这里现算)
CREATE TABLE IF NOT EXISTS api_call_log (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  request_id VARCHAR(64) NOT NULL UNIQUE, -- 幂等键 / 追踪号
  api_key_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,                -- 冗余,按人查
  task_id VARCHAR(128) NULL,              -- 关联 video_task.task_id;被拒请求为空
  endpoint VARCHAR(64) NOT NULL,
  method VARCHAR(8) NOT NULL,
  model VARCHAR(64) NULL,                 -- 请求的模型标识(被拒也有值 → 统计维度)
  provider VARCHAR(32) NULL,
  image_count INT NULL,
  duration INT NULL,
  ratio VARCHAR(32) NULL,
  megapixels DOUBLE NULL,                 -- 参数摘要;完整参数在 video_task,不重复存
  status VARCHAR(16) NOT NULL,            -- RECEIVED / SUCCESS / FAILED / REJECTED
  http_code INT NULL,                     -- 被拒时记录 400/401/403/429
  error_code VARCHAR(32) NULL,
  error_msg VARCHAR(512) NULL,
  client_ip VARCHAR(64) NULL,
  user_agent VARCHAR(255) NULL,
  cost_amount DECIMAL(12,2) NULL,         -- 从 cost_record 冗余,对账免 join
  queued_ms BIGINT NULL,                  -- 提交 → 引擎接收
  generate_ms BIGINT NULL,                -- 引擎接收 → 终态
  total_ms BIGINT NULL,                   -- 提交 → 终态
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX idx_call_log_key (api_key_id, create_time),
  INDEX idx_call_log_model (model),
  INDEX idx_call_log_task (task_id)
);

-- ③ webhook 投递(一对多重试,独立表)
CREATE TABLE IF NOT EXISTS webhook_delivery (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  task_id VARCHAR(128) NOT NULL,
  api_key_id BIGINT NOT NULL,
  status VARCHAR(16) NOT NULL,            -- 推送的任务终态(SUCCESS / FAILED)
  payload TEXT,
  http_code INT NULL,                     -- 对方响应码
  attempts INT NOT NULL DEFAULT 0,
  next_retry_at DATETIME NULL,
  delivered TINYINT(1) NOT NULL DEFAULT 0,
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_webhook_task_status (task_id, status)   -- 同任务同状态只投递一次
);
```

- `video_task` 扩展:加 `api_key_id BIGINT NULL`(判别来源 UI/API,可空,不破坏现有)。
- **统计口径全部聚合现算**:模型调用次数 `COUNT(*) GROUP BY model`、拒绝分布 `error_code` 分组、按 key 消费 `SUM(cost_amount)`、耗时分布 `queued_ms/generate_ms`。**不建计数器表**(单一事实源)。
- 保留策略:明细表可配置清理(如留 90 天)。

## 9. 设计模式(沿用现有模式语言)

| 新组件 | 模式 | 仿照现有 | 说明 |
|---|---|---|---|
| `ApiKeyInterceptor` | 拦截器链 | `AuthInterceptor` | 解析凭证 → 校验 → 注入上下文;注册进 `WebConfig`,只拦 `/api/v1/**` |
| key 认证 → 属主用户 | 适配器 | `SeedanceEngine` | API Key 凭证适配成 `UserContext`,下游零改动 |
| `ApiVideoService` | 门面 | `VideoTaskService` | Controller 薄、Service 厚 |
| `VideoSubmitService` | 共享门面 | 提取自 `VideoController` | **UI/API 共用同一份提交编排,一行业务不复制** |
| `ApiKeyService` | 门面 | `ModelAccessService` | 存储可换(MyBatis → Redis 缓存) |
| `WebhookDispatcher` | 发布-订阅 | `TaskStreamManager` | `TaskStatusChangedEvent` 的第二个监听器;内部 `WebhookNotifier` 策略 |
| API 错误 | 异常→映射 | `GlobalExceptionHandler` | `ApiException(code, httpStatus)` + handler |
| 按 key 限流 | 拦截器链 + 复用令牌桶 | `RateLimitInterceptor` | 同桶实现,维度换成 key |
| DTO | record | `VideoOptionsResponse` | 风格一致 |

**刻意不用的模式**:命令模式(门面已够)、抽象工厂(SecureRandom 工具即可)、装饰器(两阶段日志显式调用更可读)、独立任务子系统(沿用单 `video_task` + 判别列)。

## 10. 并发与一致性(四个幂等)

| 点 | 手段 |
|---|---|
| 重复提交(网络重试) | `request_id` 唯一索引,命中返回原 task_id,不重复扣费 |
| 重复下载 / 重复计费 | 现有 `updateStatus` 已幂等(白捡) |
| 重复 webhook | `(task_id, status)` 唯一索引 + 同 payload 重试 |
| 限流竞争 | 令牌桶 AtomicLong,无锁热点 |
| 日志写入 | 两阶段(接单 insert / 终态 update),写失败不影响主流程 |

## 11. 可观测性

- 每把钥匙:调用次数、成功率、消费、最后使用时间(管理页);
- 每请求:`request_id` 贯穿「拦截器 → 日志 → 任务」,报障报一个号查全链路;
- 耗时分段:`queued_ms`(排队)/ `generate_ms`(生成)/ `total_ms`(端到端);
- 拒绝分布:400 多=客户端 bug、429 多=该限、403 多=疑似扫 key。

## 12. 管理端

仿 `ModelAdminView.vue` 模式:Key 列表(状态/最后使用/今日调用/累计消费)→ 点开:按模型统计 + 最近调用明细 + 失败率。路由 `/admin/api-keys`,导航仅管理员可见。

## 13. 落地步骤(v1)

1. 提取 `VideoSubmitService`(现有 31 单测全绿做基线,行为不变);
2. `api_key` / `api_call_log` / `webhook_delivery` 三表 + entity/mapper(schema.sql);
3. `ApiKeyService`(生成/哈希/撤销/校验)+ 管理页;
4. `ApiKeyInterceptor` + `ApiException` + 错误映射;
5. `ApiVideoController` + `ApiVideoService`(幂等 → 日志 → 提交);
6. `WebhookDispatcher`(签名 + 重试 + 幂等);
7. 按 key 限流;文档(OpenAPI 风格请求/响应示例)。

## 14. 演进路线

| 版本 | 内容 |
|---|---|
| v1(本次) | 上述 7 步 |
| v2 | 月度配额、key 校验缓存、自动禁用异常 key、OpenAPI 文档、API 独立定价 |
| v3(按需) | Redis 限流/分布式、多实例、SDK 示例、IP 白名单 |

## 15. 部署注意

- API 服务走 HTTPS(钥匙传输 + webhook 安全性);
- 多实例部署时:限流换 Redis、key 校验加缓存、webhook 分发需分布式锁(或单实例跑);
- `application.yaml` 中的密钥照旧只用环境变量覆盖,不入库不入仓。

## 16. 实现状态与偏差(2026-08-05, v1 落地)

**已实现**:`VideoSubmitService`(UI/API 共享提交编排,含计费)、三表 + `video_task.api_key_id`、`ApiKeyService`(SecureRandom 生成、SHA-256 哈希、明文只返回一次)、`ApiKeyInterceptor`(Bearer 解析 → 属主用户注入 UserContext)、`ApiException` + advice(统一 error 结构 + Retry-After)、`ApiVideoController`(POST 202 / GET 状态 / GET 列表 / GET content)、`ApiModelController`(**GET /api/v1/models** 模型清单,开关过滤同 /options)、`ApiVideoService`(幂等 + 两阶段日志 + 图片 URL 转存 OSS)、`ApiCallLogUpdater`(终态收尾)、`WebhookDispatcher`(HMAC 签名 + (task_id,status) 幂等 + 30s/2m/10m 退避 3 次)、`ApiKeyRateLimitInterceptor`(`rate-limit.api-key`)、管理端 `/admin/api-keys` 页(创建/撤销/列表,敏感字段裁剪,属主显示用户名)。

**与设计的偏差**:
1. **计费触发点**:原设计保留 `GenerateCostAspect`(AOP);实现时删除切面,把 `recordOnSubmit` 收进 `VideoSubmitService.submit` —— 共享提交路径天然覆盖 UI 与 API 两条入口,不再依赖 pointcut 枚举。
2. **模型定位**:请求无 provider 字段,`model` 为注册表全局 id,由 `ApiVideoService.resolveEngineForModel` 自动定位提供方(找不到 → 400 MODEL_NOT_FOUND)。
3. **request_id**:无 `Idempotency-Key` 头时自动生成 `req_xxx`(幂等键仍建议客户端传)。
4. **幂等返回**:命中已完成幂等键 → 返回原任务(202 同 task_id);并发同键(赢家未提交完)→ 409 REQUEST_IN_PROGRESS。
5. **调用日志两阶段**:RECEIVED(接单,含 request_id 唯一)→ 终态(SUCCESS/FAILED + total_ms + 金额);提交失败 → REJECTED(含 error_code/http_code,供拒绝分布统计)。
6. **限流范围**:仅 POST(提交)消耗令牌,GET 轮询不限。

**实测修复(2026-08-06)**:
- **NPE「pk is null」**:API 不传 `ratio` 时 `Map.of.getOrDefault(null,...)` 直接 NPE(ImmutableCollections.MapN 不允许 null key,与 HashMap 不同)——5 个 ComfyUI builder 全中。修复双层:`VideoSubmitService.submit` 统一默认 duration=8/ratio=16:9(根治,UI/API 一致);各 builder 的 Map 查找前先归一(防御)。回归测试 `ZImageTurboWorkflowBuilderTest.buildWithNullRatioDoesNotNpe`。
- **错误契约被吞**:`GlobalExceptionHandler` 有 `@ExceptionHandler(Exception.class)` 兜底,而 Spring 跨 advice 取「第一个有匹配的 advice」而非最具体的 → ApiException 全被吞成 Result.fail 500。修复:`ApiExceptionHandler` 加 `@Order(HIGHEST_PRECEDENCE)`;顺带给 GlobalExceptionHandler/ApiExceptionHandler 补异常堆栈日志(此前 500 零日志)。

**v2 待办**:月度配额(QUOTA_EXCEEDED)、key 校验缓存、异常 key 自动禁用、OpenAPI 文档、API 独立定价、管理页调用统计(按模型/拒绝分布/消费,现可 SQL 现算)。
