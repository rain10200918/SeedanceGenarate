# SeedanceGenarate — 项目架构说明

> 面向「后续快速理解 / 继续开发」的速览文档，记录整体结构、核心设计、扩展点与注意事项。
> 更新于 2026-08-05。

## 1. 这是什么

一个**多提供方视频生成后端**（Spring Boot）。把「文字/图片 → 视频 / 图片」的生成能力抽象成可插拔的 provider：

- **Seedance**：火山方舟（Volcano Ark）云端 API。
- **ComfyUI**：自建多实例（同主机不同端口，共享同一套模型）。

配套能力：用户注册/登录（token 鉴权）、邀请码、按次/按秒计费、令牌桶限流、阿里云 OSS 图片存储、提示词优化（后端代理大模型）。前端为配对的 Vue3 仓库（见文末）。

## 2. 技术栈

- Java 17、Spring Boot 3.3.5、MyBatis-Plus 3.5.7、MySQL、Flyway（版本化数据库迁移）
- Hutool 5.8.27（ComfyUI HTTP 调用）、Jackson（工作流 JSON 编辑）、Lombok
- 阿里云 OSS SDK
- 前端：Vue3 + Pinia + Element Plus + axios

## 3. 构建 / 运行 / 测试

- 编译：`./mvnw clean compile`
- 单元测试：`./mvnw clean test`
  - `engine.comfyui.Impl.*WorkflowBuilderTest` 为纯 JUnit，不依赖 Spring/DB。
  - `SeedanceGenarateApplicationTests` 会启动 Spring 上下文并连接 MySQL —— **跑全量测试需要 MySQL 可用**。
- 运行：`./mvnw spring-boot:run`（端口 `SERVER_PORT`，默认 8080）
- 数据库：由 Flyway 执行 `resources/db/migration/V1__baseline.sql` 起的版本化迁移；`schema.sql` 仅保留为历史参考，默认 `spring.sql.init.mode=never` 不再启动自动执行。已有本地库可通过 `spring.flyway.baseline-on-migrate=true` 自动纳管。

## 4. 目录结构（关键包，`org.example.seedancegenarate`）

- `engine/` —— **提供方层（核心抽象）**
  - `VideoEngine`（接口）、`VideoEngineRegistry`（`Map<provider, VideoEngine>`）
  - `Impl/SeedanceEngine`、`Impl/ComfyUiEngine`
  - `GenerateCommand`（统一入参）、`SubmitResult`、`RemoteStatus` / `GenerationState`（归一化状态）
  - `GenerationMode`（TEXT_TO_VIDEO / IMAGE_TO_VIDEO）、`BillingTiming`（ON_SUBMIT / ON_SUCCESS）、`ModelSpec`（模型能力约束）
- `engine/comfyui/` —— **ComfyUI 支撑**
  - `ComfyUiProperties`（`video.comfyui.*`）、`ComfyUiClient`（HTTP：/prompt、/history、/view、/queue、/upload/image）、`ComfyUiNodeScheduler`（选节点）
  - `WorkflowBuilder`（**模型层策略**接口）、`Impl/MiniMaxH3WorkflowBuilder`（参考生视频）、`Impl/MiniMaxH3TextToVideoWorkflowBuilder`（文生视频）、`Impl/MiniMaxH3AccelWorkflowBuilder`（参考生视频·官方加速，可选 megapixels 分辨率）、`Impl/ZImageTurboWorkflowBuilder`（**文生图**，输出 PNG）、`Impl/QwenImageEditWorkflowBuilder`（**图生图**，Qwen-Image-Edit，≤3 参考图）
- `controller/` —— `VideoController`、`AuthController`、`UserAdminController`、`InviteCodeController`、`GlobalExceptionHandler`
- `service/` + `service/Impl/` —— `VideoTaskService`、`CostRecordService`、`PricingService`（`ConfigPricingService`）、`VideoDownloadService`、`OssService`、`PromptOptimizeService`、`SeedanceService`、用户/token/邀请码/限流 等
- `service/VideoSubmitService` —— **提交编排共享服务**（UI 与对外 API 共用：模型解析/闸门/落库/提交/计费）；`service/ApiKeyService`（钥匙生成/哈希/校验）、`service/ApiVideoService`（API 提交门面：幂等+两阶段日志）
- `interceptor/` —— `AuthInterceptor` + 三个限流拦截器 + `ApiKeyInterceptor`/`ApiKeyRateLimitInterceptor`（对外 API）；`config/WebConfig` 注册它们
- `config/` —— 各 `@ConfigurationProperties`；`entity/`、`mapper/`、`dto/`、`context/UserContext`、`util/`、`task/TokenCleanupTask`（清过期 token）、`task/VideoTaskPoller`（推进 PROCESSING 任务）、`task/WebhookDispatcher`（API 回调投递）、`stream/TaskStreamManager`（SSE 连接管理 + 推送）、`event/TaskStatusChangedEvent`（终态变化事件）、`event/ApiCallLogUpdater`（调用日志终态收尾）、`exception/ApiException(+Handler)`（API 错误契约）
- `resources/db/migration/*.sql` —— Flyway 数据库迁移脚本（`V1__baseline.sql` 基线、`V2__billing_idempotency.sql` 计费幂等、`V3__separate_business_and_provider_task_ids.sql` 任务 ID 分离）
- `resources/comfyui/workflows/*.json` —— ComfyUI 工作流模板

## 5. 核心设计：两层策略 + 注册表（最重要）

**第一层 — 提供方（provider）**
`VideoEngine` 接口：`provider()` / `submit(GenerateCommand)` / `poll(VideoTask)` / `models()` / `displayName()` / `billingTiming()`。所有实现由 Spring 收集进 `VideoEngineRegistry`（`List<VideoEngine>` → `Map`）。
> 新增一个提供方 = 新增一个 `@Component implements VideoEngine`，其余零改动。

**第二层 — ComfyUI 模型 / 工作流**
`ComfyUiEngine` 内部再持有 `Map<model, WorkflowBuilder>`。每个 ComfyUI 模型一份 `WorkflowBuilder`：`model()` / `spec()` / `build(command, imageFilenames)`，负责把 prompt / 图片 / 时长 / 比例注入到工作流 JSON。
> 新增一个 ComfyUI 模型 = 新增一个 `@Component implements WorkflowBuilder`（+ 一份模板 JSON），其余零改动。

`GET /api/video/options` 遍历注册表，把每个 provider 的 `models()`（即各 `ModelSpec`）下发前端，驱动「提供方 / 模型 / 比例 / 时长 / 模式」选择器 —— 因此加了新模型**前端无需改代码**即可出现并带上正确的能力约束。`ModelSpec.outputType`（`OutputType.VIDEO/IMAGE`）标记产物媒介：图片模型不下发时长、前端用 `<img>` 渲染并把生成方式文案改为「文生图 / 图生图」。`ModelSpec` 有一个 10 参向后兼容构造器默认 `VIDEO`，故已有视频 builder 零改动。

## 6. 一次生成的生命周期

1. **提交**（`POST /image2video` 或 `/text2video`）：
   - 图生视频先把参考图传 OSS 拿到 URL。
   - 落库 `video_task`（status=PROCESSING，写入 `provider`、`model`）→ `registry.get(provider).submit(cmd)` → 回写 `providerTaskId`、`nodeId`。
   - ComfyUI 的 submit：选节点 → 从 OSS URL 下载图片字节 → `/upload/image` 传到该节点 → 按 model 选 `WorkflowBuilder` 构建工作流 → `POST /prompt`。
2. **推进 + 推送**（后台 `VideoTaskPoller` + SSE，替代前端轮询）：
   - 后台推进器每 `video.poll.interval-ms`（默认 2s）扫最近 `max-age-hours` 内的 `PROCESSING` 任务 → `poll(task)` → 归一化 `RemoteStatus` → `videoTaskService.updateStatus(task, status)`。与在线客户端数无关；完成即下载，规避 Seedance 云端地址过期 / ComfyUI 历史被清。
   - `updateStatus` 落终态后发 `TaskStatusChangedEvent`（`@TransactionalEventListener` AFTER_COMMIT，提交后才推）；`TaskStreamManager` 经 SSE（`GET /api/video/stream`，`?token=` 鉴权）推给该任务所属用户的浏览器。SSE 尽力而为、非权威，DB 仍是唯一真相；断线由前端 `EventSource` 自动重连 + refetch 兜底。
   - `GET /task/{taskId}` 现为**纯读库**（不再触发远端轮询），供首屏加载与手动刷新兜底。
   - 成功：按 `provider_task_id` 查询远端产物（Seedance 云端 URL 或 ComfyUI `/view`；视频**或图片**）并**下载到本地** `data/videos/`，写 `video_url`，并 `costRecordService.recordOnSuccess`（幂等）。`VideoDownloadService` 按来源真实扩展名保存（读 ComfyUI `/view?filename=` 或 URL 路径，白名单 mp4/webm/png/jpg… ，识别不到回退 `.mp4`）；对外播放/下载时 `VideoController` 按扩展名设 `Content-Type`（图片 `<img>`、视频 `<video>`）。`ComfyUiEngine.extractVideoUrl` 已同时扫描 `gifs/videos/images`，故 SaveImage 的图片输出无需改动即可命中。
3. **播放 / 下载**：`GET /api/video/{fileName}`（内联播放）、`GET /api/video/download/{taskId}`（附件下载），均读本地文件。

**节点亲和性（ComfyUI）**：submit 选定节点后 `node_id` 落库；poll 与 `/view` 必须回到**同一节点**（队列/历史/产物都存节点本地）。
**归一化状态**：`RemoteStatus`（含 `GenerationState`）屏蔽各家状态字段差异，`updateStatus` 与提供方无关。

## 7. 计费

- 抽象：`PricingService.price(VideoTask) -> Price`；当前 `ConfigPricingService` 读 yaml（Seedance 按秒 `billing.seedance.price-per-second`；ComfyUI 每次 `billing.comfyui.flat-price`）。将来可换 DB 表而不动调用方。
- 计费时机由 `VideoEngine.billingTiming()` 决定：
  - Seedance = `ON_SUBMIT`：由 `VideoSubmitService.submit` 在引擎提交成功后记一笔（2026-08-05 原 `GenerateCostAspect` 已删除——计费收进共享提交路径，UI 与对外 API 两条入口都计费）。
  - ComfyUI = `ON_SUCCESS`：在 `updateStatus` 进入 SUCCESS 时记账。
  - `doRecord` 按 `task.id` 去重，**幂等**（前端会反复轮询）。
- 落库：`cost_record`（含 provider 判别列）+ `video_task.cost_amount`。

## 8. 鉴权与限流（`WebConfig` 注册拦截器）

- `AuthInterceptor` 拦 `/api/**`，放行 `/api/auth/login`、`/api/auth/register`。
  - token 来源：`Authorization: Bearer`、`X-Token` 头、或 `?token=` 查询参数。
  - **本地视频 `<video>` 播放依赖 `?token=`**（标签无法带自定义头）；SSE 的 `EventSource`（`/api/video/stream`）同理走 `?token=`。
- 限流（令牌桶，`rate-limit.*`）：`/api/auth/register`（按 IP）、`/api/video/image2video|text2video`（user/admin/ip）、`/api/video/optimize-prompt`（user/ip）、对外 API 提交（按钥匙 `rate-limit.api-key`，429 带 Retry-After）。
- **对外 API**（`/api/v1/**`，2026-08-05）：`ApiKeyInterceptor` 解析 `Authorization: Bearer sk-...` → 哈希比对（`api_key` 表只存 SHA-256）→ 属主用户注入 `UserContext`，下游零改动复用；错误走 `ApiException` → 统一 `{error:{code,message,request_id}}`（与 UI 的 Result 契约分离）。提交经 `ApiVideoService`（Idempotency-Key 幂等 + 两阶段 `api_call_log`）→ `VideoSubmitService`（与 UI 同一条链）；终态由 `ApiCallLogUpdater` 收尾日志、`WebhookDispatcher` 回调（HMAC 签名、`(task_id,status)` 幂等、退避重试 3 次）。模型选择 = 全局模型 id 自动定位提供方，模型开放开关同样生效。
- 角色：`UserContext.isAdmin()` 控制任务列表/详情是否跨用户可见；`/api/admin/users` 管理角色。
- 模型开放控制：`ModelAccessService` 是「模型开没开」的**唯一权威**（仿 `PricingService` 抽象）。`GET /api/video/options` 按它过滤（普通用户只见开放模型；管理员见全部并带 `open` 标记，便于开放前自测），`image2video`/`text2video` 提交时**后端硬校验**（未开放→拒绝，防手拼请求；管理员绕过）。管理员经 `/api/admin/models` 开/关；存储见 §10 `model_access`。
  - ⚠️ 提交闸门基于 **`VideoEngine.effectiveModel()` 解析后的实际生效模型**，不是请求原始 `model` 参数——2026-08-05 实测发现「不传 model / 传任意 model」可绕过闸门（Seedance 当时忽略请求 model、恒用 yaml 默认 `seedance.model`，照样生成）。修复：默认实现=显式指定则用之、为空取第一个模型；`task.model` / `command.model` 也一并写生效值。Seedance 多模型化（§11 `seedance.models`）后不再需要覆写——注册标识是真实的，直接走默认实现；单模型模式（未配置列表）注册 id 恒为 `seedance`，闸门同样拦得住。
  - `/options` 同时过滤「模型全被关闭的提供方」（普通用户不再看到空提供方，避免默认选中后无模型可用）；默认提供方若被过滤则回退到第一个可用提供方。管理员仍见全部。

## 9. HTTP 接口一览

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/auth/register` | 注册（按 IP 限流；配合邀请码体系） |
| POST | `/api/auth/login` | 登录，发 token |
| POST | `/api/auth/logout` | 注销 |
| GET | `/api/auth/me` | 当前用户 |
| POST | `/api/video/image2video` | 图生视频（multipart，字段 `images`） |
| POST | `/api/video/text2video` | 文生视频（JSON） |
| POST | `/api/video/optimize-prompt` | 提示词优化（后端代理大模型） |
| GET | `/api/video/options` | 可选提供方/模型能力（驱动前端选择器） |
| GET | `/api/video/tasks` | 任务分页 |
| GET | `/api/video/task/{taskId}` | 查状态（必要时轮询远端并落库） |
| GET | `/api/video/download/{taskId}` | 下载 mp4 |
| GET | `/api/video/{fileName}` | 内联播放本地 mp4（带 `?token=`） |
| GET | `/api/video/stream` | SSE 任务状态推送（`?token=`；替代前端轮询） |
| POST / GET | `/api/invite-codes` | 邀请码 生成 / 列表 |
| GET / PUT | `/api/admin/users` `/{userId}/role` | 用户管理 / 改角色 |
| GET / PUT | `/api/admin/models` `/{model}` | 模型开放管理 / 开关某模型（管理员） |
| GET / POST | `/api/admin/api-keys` `/{id}/revoke` | API Key 列表 / 创建（明文只返回一次）/ 撤销（管理员） |
| POST | `/api/v1/videos` | 对外 API：提交生成（`Authorization: Bearer sk-`，可选 `Idempotency-Key`；202 返回 taskId） |
| GET | `/api/v1/videos` `/{taskId}` `/{taskId}/content` | 对外 API：任务列表 / 查状态 / 下载产物 |
| GET | `/api/v1/models` | 对外 API：模型清单与能力（开关过滤同 /options） |
| — | 对外 API webhook | 终态回调 `callbackUrl`（`X-Signature` HMAC 签名） |

## 10. 数据模型（Flyway `db/migration`，MySQL）

- `app_user`：账号、角色（USER/ADMIN）、累计消费、登录/活动 IP 与时间。
- `user_token`：token → user_id + 过期时间（`TokenCleanupTask` 定期清）。
- `invite_code`：邀请码及使用状态。
- `video_task`：一条生成任务。关键判别列 **`provider` / `node_id` / `model` / `output_type`**（`output_type`=VIDEO/IMAGE，提交时按模型 `ModelSpec.outputType` 定死、冻结在记录上）；另有 `status`、`video_url`（本地路径）、`error_msg`、`cost_amount`、`images(JSON)`。
  - `biz_task_id`：系统生成的公开任务 ID，创建任务时立即生成，后续异步 Worker 以它为业务追踪 ID；
  - `provider_task_id`：Seedance / ComfyUI 返回的远端任务 ID，仅用于调用提供方和轮询；
  - 旧 `task_id`：当前兼容字段。新任务暂与 `biz_task_id` 双写，历史记录保留原值，查询同时兼容旧 `task_id` 与 `biz_task_id`。
  - **任务类型**（文生视频/图生视频/文生图/图生图）是正交派生：输入维度看 `images` 空否、输出维度看 `output_type`，用 `GenerationMode.of(hasImage, outputType)` 现算，**不单独落库**（避免与 `images` 重复→漂移）。
- `cost_record`：每笔计费；含 `provider`、`amount`、`unit_price`、`biz_type`、`task_id`。`V2__billing_idempotency.sql` 为 `task_id` 增加唯一约束，重复终态处理捕获唯一键冲突直接视为已计费。
- `model_access`：模型开放开关（管理员运行时开/关）。**稀疏覆盖**：只存被显式设过的模型（`model` 唯一 + `enabled`），没有行的模型走默认 `video.model-access.default-open`；「有哪些模型」仍以 `VideoEngineRegistry` 为准，本表只叠加开关、不作模型清单来源（避免与注册表漂移）。
- `api_key`：对外 API 钥匙（只存 SHA-256 哈希 + 前缀；明文创建时返回一次）。`api_call_log`：API 调用明细（**唯一真相**，模型次数/消费/拒绝分布全聚合现算，不建计数器表；两阶段状态 RECEIVED→SUCCESS/FAILED/REJECTED，含 request_id 幂等键、参数摘要、IP/UA、耗时分段、金额冗余）。`webhook_delivery`：回调投递（`(task_id,status)` 唯一幂等 + 重试计数）。
> 设计要点：**单一 `video_task` 生命周期**（一张表 + provider 判别 + `api_key_id` 来源判别），不为 ComfyUI / 对外 API 另开并行子系统/表，避免历史记录割裂。

## 11. 配置（`application.yaml`，均为 `${ENV:默认值}`）

- `spring.datasource.*`：MySQL 连接。
- `spring.flyway.*` / `spring.sql.init.*`：Flyway 默认启用并纳管数据库迁移；旧 `schema.sql` 初始化默认关闭。
- `seedance.*`：Seedance api-key / url；`seedance.model`（单模型模式默认 API 模型名）；`seedance.models`（多模型模式列表：`id`=注册标识/闸门 key、`name`=方舟 API 模型名、`label`=展示名；未配置时回退单模型 `id:"seedance"→name:model`）。请求里的 model 是注册标识，由 `SeedanceEngine.submit` 解析成 API 模型名后发给方舟。
- `video.default-provider`：未指定时的默认提供方（`seedance`）。
- `video.model-access.default-open`：模型无显式开关覆盖时的默认（`true`=新模型自动开放，保持「加模型零配置即可见」；改 `false` 则新模型默认隐藏、需管理员放开）。
- `video.poll.*`：后台任务推进器（`enabled` / `interval-ms` / `initial-delay-ms` / `max-age-hours` / `batch-size`）。关掉则不再自动推进任务、SSE 无增量可推（手动刷新 `GET /task` 仍可读库）。
- `spring.task.scheduling.pool.size`：定时任务线程池（默认 4）；推进器 / SSE 心跳 / token 清理各占一线程，互不阻塞。
- `video.comfyui.*`：`scheduling`（least-queue / round-robin）、连接/读超时、`nodes[]`（id / base-url / enabled）。
- `prompt-optimize.*`：提示词优化 LLM 代理 url / key / model。系统提示词**按模型选模板**：`resources/prompts/{model}.md`（缺失回退 `default.md`），运行时注入 `{imageCount}/{duration}/{ratio}` 占位并统一追加"只输出提示词本身"的输出铁律；加某模型的提示词风格 = 丢一份 `prompts/{model}.md`，零代码（已有 `minimax-h3.md` 参考生视频专用模板）。
- `file.upload-path`、`billing.*`、`rate-limit.*`、`aliyun.oss.*`、`mybatis-plus.*`。

## 12. 安全注意事项（重要）

- ⚠️ `application.yaml` 目前把**真实样式的密钥**写成了默认值（Seedance key、OSS access-key-id/secret、prompt-optimize key）。生产务必用环境变量覆盖，切勿把真实密钥提交进仓库。
- ComfyUI 节点 URL、各 API key **仅后端持有，绝不下发前端**（提示词优化正是为此走后端代理）。
- OSS 上传的参考图必须**后端可读**：ComfyUI submit 会用该 URL `downloadBytes`；私有 bucket 会 403。`aliyun.oss.domain` 为空时 `OssServiceImpl` 回退成 `https://{bucket}.{endpoint}`（保证带协议头，否则 hutool 报 "Failed to select a proxy"）。
- 对外暴露的生成接口已有限流；新增网络端点注意鉴权。

## 13. 已知事项 / TODO

- ComfyUI 五个模型（参考生视频 `minimax-h3`、文生视频 `minimax-h3-t2v`、参考生视频·官方加速 `minimax-h3-accel`、文生图 `z-image-turbo`、图生图 `qwen-image-edit`）**已于 2026-08-05 真机端到端验证通过**（submit/poll/view 全通）。部署新节点时仍需：yaml 节点 `enabled` 打开、OSS 后端可读；图片模型装对应权重——z-image（`z_image_turbo_bf16` unet、`qwen_3_4b` lumina2 clip、`ae.safetensors` vae）、qwen-image-edit（Qwen GGUF unet+clip、`qwen_image_vae`、Qwen-Image-Edit-2511-Lightning-4steps LoRA）；minimax-h3-accel（`minimax_h3_ref2va_bf16` + `nvfp4_awq` clip 加速权重，及 ResolutionSelector/CreateVideo/ComfyMathExpression/**SpectrumApplyMiniMaxH3** 等节点）。
- `minimax-h3-accel` 模板里的 `142 SpectrumApplyMiniMaxH3`（额外加速节点）**已接入采样链路**：model 链为 `127→142→(124,126)`（`BasicScheduler`/`BasicGuider` 均从 142 取 model），故该节点会真正执行、加速生效。`ModelSpec.megapixels`（可选分辨率档位）目前仅该模型非空，驱动前端「分辨率」选择器，注入 ResolutionSelector 节点算出宽高。
- `/options` 会展示 ComfyUI 即使其节点全部禁用（`models()` 与节点可用性解耦）；选中后才在 `scheduler.pick()` 抛「所有节点不可用」。可接受。
- 更多模型（Wan / Hunyuan / LTX / 更多图片模型 …）= 各加一个 `WorkflowBuilder` + 一份工作流模板 JSON；图片模型复用 `OutputType.IMAGE` + 下载/播放的媒介类型链路（见 `z-image-turbo`）。
- **SSE 实时推送**（`VideoTaskPoller` + `TaskStreamManager`，`event/TaskStatusChangedEvent`）替代前端轮询：`GET /task` 改纯读库、前端 `EventSource` 订阅 `/api/video/stream`。行为变化：任务现由后台推进器**持续推进 + 成功即计费，即使用户关掉页面**（旧行为需客户端 GET 才推进）。部署注意：反向代理（nginx 等）对 `/api/video/stream` 需关缓冲（`proxy_buffering off;` / `X-Accel-Buffering: no`）否则 SSE 不实时；当前为**单实例假设**——多实例后端会各自推进同一批任务，需加分布式锁或只在一台跑推进器（`video.poll.enabled=false` 关掉其余实例）。

## 14. 相关

- 配对前端仓库：`/Users/a1234/WebstormProjects/seedance_generate/src`（Vue3 + Pinia + Element Plus）。生成页 `views/GenerateView.vue` 由 `/options` 驱动。
- ComfyUI 工作流注入点细节：见各 `WorkflowBuilder` 与 `resources/comfyui/workflows/*.json` 的对照。
