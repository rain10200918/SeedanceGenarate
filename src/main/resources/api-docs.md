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

## 2. Base URL 与约定

```
https://api-generate.creator.ascent-ai.cn/api/v1
```

- 所有接口均需携带 `Authorization: Bearer <key>` 头；
- 请求与响应均为 `application/json`（下载接口除外）；
- 失败统一返回标准错误结构（见 §7）；
- 提交类接口有**按钥匙限流**（默认 10 次/分钟，突发 5），超限返回 `429` 并带 `Retry-After` 响应头；
- 全链路支持标准 HTTPS 安全传输。

## 3. 接口一览

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/videos` | 提交生成任务（异步处理，返回 202 Accepted） |
| GET | `/videos/{taskId}` | 查询任务状态与产物 URL |
| GET | `/videos` | 任务列表（支持分页查询） |
| GET | `/videos/{taskId}/content` | 下载生成的视频/图片产物（重定向至安全签名下载 URL） |
| GET | `/models` | 查询当前开放可用的模型清单与参数能力 |
| GET | `/videos/docs` | 本文档（原始 Markdown 格式） |

## 4. 提交生成任务

```
POST /api/v1/videos
```

### 请求体参数

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `prompt` | string | ✅ | 提示词，描述画面、镜头运动、光影风格 |
| `model` | string | ✅ | 模型标识（见 §6 `GET /models`，如 `minimax-h3-fl2va-hd`） |
| `images` | string[] | 图生必填 | 参考图 URL 列表（`fl2va` 模式传 2 张：第 1 张首帧，第 2 张尾帧） |
| `duration` | int | 视频模型 | 时长（秒），支持 5-15 秒（如 6, 8, 10 等），默认 6 或 8 |
| `ratio` | string | 否 | 画面比例，如 `16:9`、`9:16`、`1:1`、`4:3`、`3:4`，默认 `16:9` |
| `megapixels` | number | 否 | 清晰度像素档位（如高清模型支持 `0.3` ~ `0.5`） |

> **没有 `mode` 字段。** 生成模式由「所选模型的能力 + 是否传了 `images`」共同决定：
> 文生类模型不传图，参考生成类模型按模型要求的张数传图（`minimax-h3-fl2va-hd` 传 2 张，
> 第 1 张首帧、第 2 张尾帧）。请求里多传的字段会被忽略。

### 请求头

| 头字段 | 必填 | 说明 |
|---|---|---|
| `Authorization` | ✅ | `Bearer sk-xxxxxxxx...` |
| `Content-Type` | ✅ | `application/json` |
| `Idempotency-Key` | 推荐 | 客户端全局唯一幂等键（UUID），防网络抖动重复扣费 |

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
    "mode": "fl2va",
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

## 5. 幂等控制（Idempotency）

提交请求携带 `Idempotency-Key` 请求头（推荐使用标准 UUID）：
- 同一 Key **在网络超时重试时返回同一个 `taskId`**，不会重复扣费，不会重复创建 GPU 任务；
- 建议 Key 长度 16~64 位字符。

## 6. 查询与下载

### 查询任务状态

```
GET /api/v1/videos/{taskId}
```

```json
{
  "taskId": "tsk_4cbf4eaa9899469592737c2e148702f3",
  "status": "SUCCESS",
  "model": "minimax-h3-t2v-hd",
  "costAmount": 1.80,
  "videoUrl": "tsk_4cbf4eaa9899469592737c2e148702f3.mp4",
  "artifactExpired": false,
  "errorMsg": null
}
```

- `status` 取值：
  - `PROCESSING`：排队或 GPU 渲染中；
  - `SUCCESS`：生成成功，请用下方「下载产物」接口取文件；
  - `FAILED`：生成失败，`errorMsg` 为具体错误原因。
- `videoUrl` 是**产物文件名，不是可直接访问的地址**。取文件一律走
  `GET /api/v1/videos/{taskId}/content`（该接口会 302 到一条短期签名链接）。
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

自动重定向并下载生成的视频（`video/mp4`）或图片（`image/png`）文件：

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
| `qwen-image-edit` | Qwen-Image-Edit 图生图 | 图片 |
| `flux2-image-edit` | Flux 2.0 图像编辑 | 图片 |

每个模型支持的比例、时长范围、参考图张数以 `GET /api/v1/models` 返回的字段为准，
不要按上表推断——传错会被 `VALIDATION_ERROR` 拒绝。

## 7. 错误响应码

发生错误时统一返回标准结构：

```json
{
  "error": {
    "code": "MODEL_NOT_FOUND",
    "message": "模型不存在: xxx",
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
| 410 | `ARTIFACT_EXPIRED` | 产物已过 30 天保留期并被删除，**重试无用**，需重新提交生成 | ❌ 重新生成 |
| 402 | `INSUFFICIENT_BALANCE` | 账号可用余额不足，请先充值 | ❌ 充值后重试 |
| 429 | `RATE_LIMITED` | 触发并发或速率限制，请参考 `Retry-After` 头 | ✅ 延迟重试 |
| 503 | `PROVIDER_UNAVAILABLE` | GPU 节点全忙或渲染集群临时维护 | ✅ 指数退避重试 |
| 500 | `INTERNAL_ERROR` | 服务器内部未知异常 | ✅ 带幂等键重试 |

## 8. Webhook 异步回调通知

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

## 9. 完整快速接入示例（Python）

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
