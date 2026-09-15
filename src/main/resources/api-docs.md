# 视频生成 API 接入文档

> 通过 API Key 调用本服务的视频 / 图片生成能力。生成是**异步**的：提交后立即返回 `taskId`，
> 之后轮询状态或配置 webhook 接收完成回调。

## 1. 获取 API Key

在平台控制台或向管理员申请。创建后**明文只展示一次**，请立即妥善保存：

```
sk-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
```

调用时放入 HTTP 请求头：

```
Authorization: Bearer sk-xxxxxxxx...
```

> Key 可被管理员随时撤销；撤销后立即失效。Key 绑定一个属主账号，产生的任务与费用都记在该账号名下。

### 月度消费预算

`GET /api/v1/account/key-budget` 使用当前 Key 查询 `{apiKeyId,limit,consumed,reserved,remaining,period,resetAt,currency,version}`。金额单位为人民币元；`limit`/`remaining` 为 `null` 表示不限，月份按上海自然月计算。预算与账号余额同时约束新的付费受理，设置预算不划拨账号资金。预算不足返回403 `API_KEY_SPENDING_LIMIT_EXCEEDED`；账号余额不足仍为402 `INSUFFICIENT_BALANCE`。

预算只能在登录控制台管理，API凭证没有修改接口；管理员可调整上限，属主只能收紧。零上限禁止新付费任务，免费任务、已有任务查询和原请求重放保持可用。跨月完成计入提交月份；首月仅统计新版本受理的任务，旧任务不补算预算。

## 2. Base URL 与约定

```
https://api-generate.creator.ascent-ai.cn/api/v1
```

- 所有接口均需携带 `Authorization: Bearer <key>` 头；
- 请求与响应均为 `application/json`（下载接口除外）；
- 失败统一返回标准错误结构（见 §8）；
- 提交类接口**按账号共享限流**，不是每把钥匙独立额度；个人默认桶容量 10、每 60 秒补充 5，企业可按席位策略调整，超限返回 `429` 并带 `Retry-After`；
- 全链路支持标准 HTTPS 安全传输。

## 3. 接口一览

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/generations/quote` | 计算真实提交将冻结的金额（无写入） |
| POST | `/assets/upload-url` | 获取参考图 OSS 直传表单凭证（P0 仅图片） |
| POST | `/prompts/optimize` | 按目标模型优化提示词（P0 免费） |
| POST | `/videos` | 提交生成任务（异步处理，返回 202 Accepted） |
| GET | `/videos/{taskId}` | 查询任务状态与产物 URL |
| GET | `/videos` | 任务列表（支持分页查询） |
| GET | `/videos/{taskId}/content` | 下载生成的视频/图片/音频产物（OSS 产物重定向至安全签名下载 URL） |
| GET | `/models` | 查询当前开放可用的模型清单与参数能力 |
| GET | `/account/balance` | 查询 API Key 对应账号的余额 |
| GET | `/videos/docs` | 本文档（原始 Markdown 格式） |

## 4. 生成前辅助接口

### 4.1 获取报价

```http
POST /api/v1/generations/quote
Content-Type: application/json
```

```bash
curl -X POST https://api-generate.creator.ascent-ai.cn/api/v1/generations/quote \
  -H "Authorization: Bearer sk-xxxxxxxx" \
  -H "Content-Type: application/json" \
  -d '{"model":"minimax-h3-t2v-hd","duration":6,"resolution":"2k"}'
```

```json
{
  "provider": "comfyui",
  "model": "minimax-h3-t2v-hd",
  "duration": 6,
  "outputType": "VIDEO",
  "unitPrice": 0.30,
  "amount": 1.80,
  "currency": "CNY",
  "resolution": "2k",
  "megapixels": 0.9
}
```

`amount` 与紧接着提交同样参数时的冻结金额使用同一计价链路。报价本身不创建任务、
不写调用日志、不冻结余额。价格可能由管理员调整，应在提交前实时调用。

报价可选字段 `resolution`、`megapixels` 与提交使用相同校验及映射；当前费用仍按模型/时长计算，
不按 MP 加价。未传 `resolution` 时响应该字段为 null，未传两个字段时保持旧工作流默认，响应 MP 为 null。

### 4.2 上传本地参考图

P0 仅支持 `image/jpeg`、`image/png`、`image/webp`，单文件最大 30 MiB。先申请受限凭证：

```bash
curl -X POST https://api-generate.creator.ascent-ai.cn/api/v1/assets/upload-url \
  -H "Authorization: Bearer sk-xxxxxxxx" \
  -H "Content-Type: application/json" \
  -d '{"filename":"first-frame.png","contentType":"image/png","sizeBytes":123456}'
```

响应示例（字段值有截断）：

```json
{
  "method": "POST",
  "uploadUrl": "https://bucket.oss-cn-beijing.aliyuncs.com",
  "fields": {
    "key": "api-uploads/42/3c0f...a91.png",
    "Content-Type": "image/png",
    "success_action_status": "204",
    "policy": "eyJleHBpcmF0aW9uIjo...",
    "OSSAccessKeyId": "LTAI...",
    "Signature": "..."
  },
  "assetUrl": "https://bucket.oss-cn-beijing.aliyuncs.com/api-uploads/...?Expires=...",
  "uploadExpiresAt": "2026-09-03T08:10:00Z",
  "assetExpiresAt": "2026-09-03T09:00:00Z"
}
```

将 `fields` 的每个字段原样放入 `multipart/form-data`，**`file` 必须最后放**：

```bash
curl -X POST "$uploadUrl" \
  -F "key=$key" \
  -F "Content-Type=image/png" \
  -F "success_action_status=204" \
  -F "policy=$policy" \
  -F "OSSAccessKeyId=$OSSAccessKeyId" \
  -F "Signature=$Signature" \
  -F "file=@first-frame.png;type=image/png"
```

上传成功会返回 HTTP `204`。随后把 `assetUrl` 放入 `/videos` 的 `images`，并在
`assetExpiresAt` 前提交。凭证只能写入当前 API Key 属主的随机 staging key，且会校验 MIME
与声明字节数；未被任务消费的 `api-uploads/` 对象应由 OSS 生命周期规则在 1 天后清理。

### 4.3 优化提示词

```bash
curl -X POST https://api-generate.creator.ascent-ai.cn/api/v1/prompts/optimize \
  -H "Authorization: Bearer sk-xxxxxxxx" \
  -H "Content-Type: application/json" \
  -d '{
    "prompt":"雨夜里的跑车",
    "model":"minimax-h3-t2v-hd",
    "imageCount":0,
    "videoCount":0,
    "audioCount":0,
    "duration":6,
    "ratio":"16:9"
  }'
```

```json
{
  "originalPrompt": "雨夜里的跑车",
  "optimizedPrompt": "超广角电影镜头下……",
  "model": "minimax-h3-t2v-hd"
}
```

`prompt` 最长 5000 字符；参考素材数量各为 0~20，`duration` 为 1~600。显式传入的
`model` 必须存在且已开放。P0 不扣除钱包余额，但有按账号与 IP 的共享 Redis 限流。

## 5. 提交生成任务

```
POST /api/v1/videos
```

### 请求体参数

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `prompt` | string | ✅ | 非空提示词，最长 5000 字符 |
| `model` | string | ✅ | 模型标识（见 §7 `GET /models`，如 `minimax-h3-fl2va-hd`） |
| `images` | string[] | 图生必填 | 参考图 URL 列表（`fl2va` 模式传 2 张：第 1 张首帧，第 2 张尾帧） |
| `videos` | string[] | 否 | 参考视频 URL 列表；仅支持该模型声明 `videoMax > 0` 的参考生成模型 |
| `audios` | string[] | 否 | 参考音频 URL 列表；仅支持该模型声明 `audioMax > 0` 的参考生成模型 |
| `duration` | int | 否 | 必须命中模型公布的时长；省略时优先合法的 8 秒，否则首个合法时长；图片无时长，仅兼容历史占位 1/8（内部统一为 8），建议省略 |
| `ratio` | string | 否 | 必须命中模型比例；省略时优先 `16:9`，否则首项；无画幅能力的模型请省略 |
| `megapixels` | number | 否 | 必须是模型公布的有限数值档位，不是连续区间；不提供档位的模型请省略 |
| `resolution` | string | 否 | 精确小写 `480p` / `720p` / `1080p` / `2k` / `4k`，仅可使用模型 `resolutions` 中的 ID |

新调用方选择档位后推荐只传 `resolution`；同时传 `megapixels` 时必须等于模型公布的映射值，否则返回400。
固定档位的映射 MP 为 null，此时省略 `megapixels`，由原固定工作流执行。
空白、未知或模型不支持的档位均在参考素材下载及任务创建前拒绝；省略新字段保持旧 MP/default 语义。
同一 Idempotency-Key 的原请求重放不重新解释历史任务；改变请求内容仍按既有规则返回409。

> **没有 `mode` 字段。** 生成模式由「所选模型的能力 + 是否传了 `images` / `videos` / `audios`」共同决定：
> 文生类模型不传图，参考生成类模型按模型要求的张数传图（`minimax-h3-fl2va-hd` 传 2 张，
> 第 1 张首帧、第 2 张尾帧）。`videos` / `audios` 传入的是外部可访问的媒体 URL，服务端会先安全下载并转存 OSS；
> 数量上限以 `GET /api/v1/models` 返回的 `videoMax` / `audioMax` 为准。请求里的未定义字段会被忽略。

新请求在下载素材、创建任务和冻结余额前校验参数；非法时长不再自动截短，报价和提交使用同一时长规则。
所有素材合计最多 16 项，每条 URL 非空且最长 4096 字符，全部地址预检后才开始下载。
每个素材最多 30MiB，合计最多 100MiB；JSON 请求体最多 64KiB，超限返回 `413 PAYLOAD_TOO_LARGE`。
禁止内网/本地地址，每次重定向重新检查；连接和单次读取超时 5 秒，最多 3 次重定向。
URL 不允许内嵌用户名密码；显式端口必须在 1–65535 内。
素材转存使用本次请求独立对象：下载、上传或明确未受理时尽力清理；任务已受理或状态无法确认时保留。
进程中断或 OSS 删除失败仍可能留下未使用对象，此处不承诺事务式回收。

### 请求头

| 头字段 | 必填 | 说明 |
|---|---|---|
| `Authorization` | ✅ | `Bearer sk-xxxxxxxx...` |
| `Content-Type` | ✅ | `application/json` |
| `Idempotency-Key` | 推荐 | 客户端全局唯一幂等键（推荐 UUID，最长 64 字符），防网络抖动重复扣费 |

### 示例 1：文生视频 / 图生视频高清版

```bash
curl -X POST https://api-generate.creator.ascent-ai.cn/api/v1/videos \
  -H "Authorization: Bearer sk-xxxxxxxxxxxxxxxxxxxxxxxx" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: $(uuidgen)" \
  -d '{
    "prompt": "赛博朋克雨夜街道，霓虹灯倒影在积水路面，镜头电影感缓慢推近，4K 超清",
    "model": "minimax-h3-t2v-hd",
    "duration": 6,
    "ratio": "16:9",
    "megapixels": 0.4
  }'
```

### 示例 2：首尾帧高清视频生成（`minimax-h3-fl2va-hd`）

```bash
curl -X POST https://api-generate.creator.ascent-ai.cn/api/v1/videos \
  -H "Authorization: Bearer sk-xxxxxxxxxxxxxxxxxxxxxxxx" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: $(uuidgen)" \
  -d '{
    "prompt": "蜘蛛侠从大楼顶端纵身跃下，穿梭在城市高楼之间",
    "model": "minimax-h3-fl2va-hd",
    "images": [
      "https://your-domain.com/first-frame.jpg",
      "https://your-domain.com/last-frame.jpg"
    ],
    "duration": 6,
    "ratio": "16:9",
    "megapixels": 0.3
  }'
```

### 响应（202 Accepted）

```json
{
  "taskId": "tsk_4cbf4eaa9899469592737c2e148702f3",
  "status": "PROCESSING",
  "requestId": "req_19356f622d00495bace8e116d8f94852"
}
```

### 示例 3：视频 + 音频参考生成（`minimax-h3-4step`）

```bash
curl -X POST https://api-generate.creator.ascent-ai.cn/api/v1/videos \
  -H "Authorization: Bearer sk-xxxxxxxxxxxxxxxxxxxxxxxx" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: $(uuidgen)" \
  -d '{
    "prompt": "保留参考视频的运动节奏，并参考音频的人声质感生成一段新视频",
    "model": "minimax-h3-4step",
    "videos": ["https://your-domain.com/reference-motion.mp4"],
    "audios": ["https://your-domain.com/voice-reference.mp3"],
    "duration": 6,
    "ratio": "16:9"
  }'
```

`POST /api/v1/videos` 当前只接收 JSON 中的 URL 列表，不接收 `multipart/form-data` 文件；URL 必须能被服务端访问，
并会经过 SSRF、响应状态、超时和单文件 30 MiB 限制校验。

## 6. 幂等控制（Idempotency）

提交请求携带 `Idempotency-Key` 请求头（推荐使用标准 UUID）：

- 同一幂等键、相同请求在网络超时重试时恢复同一个 `taskId`，不重复生成或扣费；尚未受理完返回 `409 REQUEST_IN_PROGRESS`，稍后使用原键重试。
- 新请求保存参数指纹；同一个键更换 prompt/model/媒体列表/时长/比例/分辨率，返回 `409 IDEMPOTENCY_KEY_REUSED`。新创作必须使用新键。
- 指纹对 prompt/model/URL 去首尾空格，省略媒体列表等价空数组，媒体顺序保留；省略可选参数与显式传入默认值视为不同请求。仅比较请求参数，不下载素材来比较内容。
- 幂等键去首尾空格后必须 1–64 字符，推荐 UUID；仍使用原有全局唯一范围，请勿在不同 API Key 间复用。
- 仅省略该请求头时自动生成新键；显式空白或包含控制字符返回400。请求参数包含非法 Unicode 代理字符也会被拒绝，不静默替换。
- 历史无指纹日志仍按旧任务恢复，不回填猜测的指纹；不同参数冲突检查仅适用于上线后带指纹的新请求。

## 7. 查询与下载

### 查询账户余额

```
GET /api/v1/account/balance
Authorization: Bearer sk-xxxxxxxx...
```

该接口使用与其他 `/api/v1/**` 接口相同的 API Key 认证。余额属于当前 API Key 对应的账号，
请求不接受 `userId` 等账号查询参数。

成功响应：

```json
{
  "available": 98.20,
  "frozen": 1.80,
  "total": 100.00,
  "currency": "CNY"
}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `available` | number | 当前可用于提交生成任务的余额 |
| `frozen` | number | 已被处理中任务冻结、尚未结算或退回的余额 |
| `total` | number | 账号总余额，始终等于 `available + frozen` |
| `currency` | string | 币种，当前固定为 `CNY` |

### 查询任务状态

```
GET /api/v1/videos/{taskId}
```

```json
{
  "taskId": "tsk_4cbf4eaa9899469592737c2e148702f3",
  "status": "SUCCESS",
  "model": "minimax-h3-t2v-hd",
  "outputType": "VIDEO",
  "costAmount": 1.80,
  "videoUrl": "tsk_4cbf4eaa9899469592737c2e148702f3.mp4",
  "artifactExpired": false,
  "errorMsg": null
}
```

查询和列表的 `records` 只返回公开字段：`taskId`、`status`、`model`、`outputType`、`duration`、`ratio`、
`costAmount`、`videoUrl`、`artifactExpired`、`errorMsg`、`processingState`、`processingMessage`、
`moderationStatus`、`moderationReasonCode`、`moderationMessage`。不再返回数据库ID、API Key/账号ID、
节点、冻结快照、内部时间、原始参考素材地址或对象存储key。`errorMsg` 为安全公开说明，不含上游原始异常。
列表分页保留 `records/total/size/current/pages`；不要依赖ORM内部分页属性。
`outputType` 为 `VIDEO`、`IMAGE` 或 `AUDIO`；路径和兼容字段 `videoUrl` 对音频也保持不变。
本地音频下载返回对应 MIME（MP3 `audio/mpeg`、M4A `audio/mp4`、AAC `audio/aac`、WAV `audio/wav`、OGG `audio/ogg`、FLAC `audio/flac`）；未知格式为 `application/octet-stream`。OSS 重定向后的 MIME 使用产物上传时的对象元数据。新音频转存同时使用相应后缀与 MIME；本次不会自动重写历史对象元数据或文件名。

- `status` 取值：
  - `PROCESSING`：排队或 GPU 渲染中；
  - `SUCCESS`：生成成功，请用下方「下载产物」接口取文件；
  - `FAILED`：生成失败，`errorMsg` 为安全公开说明，详细原因由平台查询。
- `videoUrl` 是**产物文件名，不是可直接访问的地址**。取文件一律走
  `GET /api/v1/videos/{taskId}/content`（OSS 产物 302 到短期签名链接，本地历史产物直接返回文件）。
- `artifactExpired`：产物是否已过保留期（见下方「产物保留期」）。`true` 时不要再请求
  `/content`，它会返回 `410`。

### 产物保留期

**生成的产物只保留 30 天**，到期后由对象存储自动删除，**不可恢复**。

- 需要长期留存的，请在拿到结果后**尽快下载到自己这边**；
- 过期后 `GET /api/v1/videos/{taskId}/content` 返回 `410 GONE` / `ARTIFACT_EXPIRED`；
- 任务记录本身不删除，`GET /api/v1/videos/{taskId}` 仍可查到，只是 `artifactExpired` 为 `true`。

### 下载产物

```
GET /api/v1/videos/{taskId}/content
```

下载生成的视频、图片或音频文件（OSS 产物自动重定向）。下例为 MP4；音频请按实际格式保存为 `.mp3`、`.wav` 等：

```bash
curl -L -s -o result.mp4 https://api-generate.creator.ascent-ai.cn/api/v1/videos/{taskId}/content \
  -H "Authorization: Bearer sk-xxxxxxxx"
```

### 可用模型清单

```
GET /api/v1/models
```

**以该接口的实时返回为准** —— 模型是否对您的 Key 开放由平台侧配置，下表只是当前内置清单。

| 模型标识 | 名称 | 产物 |
|---|---|---|
| `minimax-h3-t2v` | MiniMax-H3 文生视频 | 视频 |
| `minimax-h3-t2v-hd` | MiniMax-H3 文生视频 高清版 | 视频 |
| `minimax-h3` | MiniMax-H3 参考生视频 | 视频 |
| `minimax-h3-accel` | MiniMax-H3 参考生视频 官方加速 | 视频 |
| `minimax-h3-4step` | MiniMax-H3 4-step 多参考生视频 | 视频 |
| `minimax-h3-opt` | MiniMax-H3 多参生视频 优化版 | 视频 |
| `minimax-h3-hd` | MiniMax-H3 多参生视频 高清优化版 | 视频 |
| `minimax-h3-fl2va-hd` | MiniMax-H3 首尾帧生视频 高清版 | 视频 |
| `z-image-turbo` | Z-Image-Turbo 文生图 | 图片 |
| `krea2-turbo` | Krea2 Turbo 文生图 | 图片 |
| `qwen-image-edit` | Qwen-Image-Edit 图生图 | 图片 |
| `flux2-image-edit` | Flux 2.0 图像编辑 | 图片 |
| `minimax-music3` | MiniMax Music 3 音乐生成 | 音频 |

每个模型支持的比例、时长范围、参考图张数以 `GET /api/v1/models` 返回的字段为准，
不要按上表推断——传错会被 `VALIDATION_ERROR` 拒绝。

能力字段还包括 `videoMax`、`audioMax`（0 表示不支持该参考类型）、
`needImageOrVideo`（至少一张图或一段视频，只有音频不满足）及 `imageInputMode`：
`NONE` / `UNSPECIFIED` / `REFERENCE_IMAGE` / `FIRST_FRAME` / `FIRST_LAST_FRAME`。
首尾帧模式仍按 `images` 顺序传首帧、尾帧。

清晰度能力新增 `resolutions: [{"id":"2k","megapixels":0.9,"upscaled":true}]` 与
`defaultResolution: string|null`。前者仅列出该模型支持的档位，未知能力为空数组；单个固定档位的
`megapixels` 为 null。`upscaled` 表示实际输出链经过超分，不能根据模型名字推断。
`defaultResolution` 供新界面初始选择，不改变省略字段的旧请求默认。
2K 指约2560×1440级别；档位按总像素量近似映射，其他画幅不保证精确短边尺寸。
HD 模型720p/1080p/2k分别映射输入0.2/0.5/0.9 MP；缺少4K能力时不会下发4k。
Flux2 的480p/720p/1080p分别映射0.5/1.0/2.0 MP。完整可选项以模型实时响应为准。

## 8. 错误响应码

发生错误时统一返回标准结构：

```json
{
  "error": {
    "code": "MODEL_NOT_FOUND",
    "message": "模型不存在",
    "requestId": "req_xxxxxxxx"
  }
}
```

| HTTP 状态码 | Error Code | 含义与排查说明 | 是否可重试 |
|---|---|---|---|
| 400 | `VALIDATION_ERROR` | 参数缺失或非法（如提示词为空、时长超出范围） | ❌ 修改参数后重试 |
| 400 | `MODEL_NOT_FOUND` | 模型标识不存在 | ❌ |
| 401 | `INVALID_API_KEY` | API Key 不存在或格式错误 | ❌ |
| 403 | `API_KEY_DISABLED` | API Key 已被禁用或欠费 | ❌ |
| 403 | `API_KEY_EXPIRED` | API Key 已过有效期 | ❌ |
| 403 | `MODEL_NOT_OPEN` | 该模型当前未对您的账号开放 | ❌ |
| 404 | `TASK_NOT_FOUND` | 任务编号不存在或不属于该 API Key | ❌ |
| 404 | `NOT_FOUND` | API 路径或资源不存在 | ❌ |
| 405 | `METHOD_NOT_ALLOWED` | 请求方法不支持，可查看 `Allow` 响应头 | ❌ |
| 406 | `NOT_ACCEPTABLE` | 不支持请求的响应媒体类型 | ❌ |
| 409 | `REQUEST_IN_PROGRESS` | 同一幂等键的请求仍在处理 | ✅ 原键退避重试 |
| 409 | `IDEMPOTENCY_KEY_REUSED` | 同一幂等键用于不同请求参数 | ❌ 新创作请换新键 |
| 410 | `ARTIFACT_EXPIRED` | 产物已过 30 天保留期并被删除，**重试无用**，需重新提交生成 | ❌ 重新生成 |
| 413 | `PAYLOAD_TOO_LARGE` | 生成 JSON 请求体超过 64KiB | ❌ 缩小请求体后重试 |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | 不支持的请求 Content-Type | ❌ 使用文档规定类型 |
| 402 | `INSUFFICIENT_BALANCE` | 账号可用余额不足，请先充值 | ❌ 充值后重试 |
| 429 | `RATE_LIMITED` | 触发并发或速率限制，请参考 `Retry-After` 头 | ✅ 延迟重试 |
| 503 | `RATE_LIMIT_UNAVAILABLE` | 共享 Redis 限流暂不可用；提示词优化入口会失败关闭 | ✅ 延迟重试 |
| 503 | `UPLOAD_CREDENTIAL_UNAVAILABLE` | OSS 直传凭证暂时无法签发 | ✅ 延迟重试 |
| 503 | `PROMPT_OPTIMIZE_UNAVAILABLE` | 提示词优化的 LLM 通道暂不可用 | ✅ 延迟重试 |
| 503 | `PROVIDER_UNAVAILABLE` | GPU 节点全忙或渲染集群临时维护 | ✅ 指数退避重试 |
| 500 | `INTERNAL_ERROR` | 服务器内部未知异常 | ✅ 带幂等键重试 |

## 9. Webhook 异步回调通知

### 配置与密钥管理

以下是登录用户自助管理接口，使用平台登录凭证，**不是** `/api/v1` 的 `sk-` 接口：

| 方法 | 路径 | 请求/结果 |
|---|---|---|
| POST | `/api/api-keys` | 创建响应 `data` 增加一次性 `webhookSecret`，与 `plainKey` 一起立即保存 |
| PATCH | `/api/api-keys/{id}/callback` | `{ "callbackUrl": "https://your-host/callback" }` 更新；null 或空串清除 |
| POST | `/api/api-keys/{id}/webhook-secret/rotate` | 响应 `data.webhookSecret` 一次性返回新值，旧值立即失效 |

只能操作自己的在用 API Key；列表永远不显示 secret。创建或轮换响应丢失时，不提供读取旧 secret 接口，请再次轮换并同步客户端验签配置。
callback 仅允许 HTTPS 公网地址（最长 512 字符），禁止内嵌用户名密码、fragment、非法端口、内网/本地地址及 HTTP 重定向。
保存前及每次发送前均校验 DNS；实际连接只使用该次已验证的公网地址，保留 TLS 证书和主机名验证。旧 HTTP/不安全地址也会停止投递，请更新配置。
清除 callback 或撤销 Key 后停止后续投递；已经在途的请求无法撤回。轮换后后续尝试使用新 secret，没有旧 secret 宽限期。
回调是至少一次交付，接收方仍须按 `(task_id, status)` 去重；最多投递 3 次（含首发），失败后约 30 秒、2 分钟重试，实际时间受扫描周期影响。明确不安全的目标会停止投递，临时网络/DNS故障按有限次数退避。

如果您在平台配置了 `callbackUrl`，任务完成时系统会自动向您的服务器发送 POST 通知：

```
POST {callbackUrl}
Content-Type: application/json
X-Signature: <HMAC-SHA256 十六进制签名>
```

回调数据示例：

```json
{
  "task_id": "tsk_4cbf4eaa9899469592737c2e148702f3",
  "status": "SUCCESS",
  "output_type": "VIDEO",
  "video_url": "tsk_4cbf4eaa9899469592737c2e148702f3.mp4",
  "error": null,
  "cost_amount": 1.80
}
```

字段说明：

- `video_url` 与查询接口一致，是**产物文件名，不是可直接访问的地址**；
  收到回调后请用 `GET /api/v1/videos/{task_id}/content` 取文件。
- `status` 为 `FAILED` 时 `video_url` 为 `null`，失败原因见 `error`。

### 签名校验算法（Python 示例）

```python
import hmac, hashlib, json

def verify_signature(secret: str, raw_body: str, signature: str) -> bool:
    expected = hmac.new(secret.encode('utf-8'), raw_body.encode('utf-8'), hashlib.sha256).hexdigest()
    return hmac.compare_digest(expected, signature)
```

## 10. 完整快速接入示例（Python）

```python
import requests
import time
import uuid

BASE_URL = "https://api-generate.creator.ascent-ai.cn/api/v1"
API_KEY = "sk-xxxxxxxxxxxxxxxxxxxxxxxx"

headers = {
    "Authorization": f"Bearer {API_KEY}",
    "Content-Type": "application/json",
    "Idempotency-Key": str(uuid.uuid4())
}

# 1. 提交生成任务
payload = {
    "prompt": "赛博朋克跑车在雨夜街道疾驰，车尾霓虹流光，电影画质",
    "model": "minimax-h3-t2v-hd",
    "duration": 6,
    "ratio": "16:9",
    "megapixels": 0.4
}

resp = requests.post(f"{BASE_URL}/videos", headers=headers, json=payload)
data = resp.json()
task_id = data["taskId"]
print(f"任务已提交，任务 ID: {task_id}")

# 2. 轮询任务状态
while True:
    status_resp = requests.get(f"{BASE_URL}/videos/{task_id}", headers=headers).json()
    status = status_resp["status"]
    print(f"当前任务状态: {status}")
    if status == "SUCCESS":
        # videoUrl 是文件名，不是地址；取文件走 /content（会 302 到短期签名链接）
        content = requests.get(f"{BASE_URL}/videos/{task_id}/content",
                               headers=headers, allow_redirects=True)
        if content.status_code == 410:
            print("产物已过期（保留 30 天），需重新生成")
            break
        with open("result.mp4", "wb") as f:
            f.write(content.content)
        print("生成成功，已保存 result.mp4")
        break
    elif status == "FAILED":
        print(f"生成失败: {status_resp.get('errorMsg')}")
        break
    time.sleep(5)
```
