# 视频生成 API 接入文档

> 通过 API Key 调用本服务的视频 / 图片生成能力。生成是**异步**的:提交后立即返回 `task_id`,
> 之后轮询状态或配置 webhook 接收完成回调。

## 1. 获取 API Key

向平台管理员申请。创建后**明文只展示一次**,请立即保存:

```
sk-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
```

调用时放入请求头:

```
Authorization: Bearer sk-xxxxxxxx...
```

> Key 可被管理员随时撤销;撤销后立即失效。Key 绑定一个属主账号,产生的任务与费用都记在该账号名下。

## 2. Base URL 与约定

```
http://123.58.111.244:9090/api/v1
```

- 所有接口均需携带 `Authorization: Bearer <key>` 头;
- 请求与响应均为 `application/json`(下载接口除外);
- 失败统一返回错误结构(见 §7);
- 提交类接口有**按钥匙限流**(默认 10 次/分钟,突发 5),超限返回 `429` 并带 `Retry-After` 响应头。

## 3. 接口一览

| 方法 | 路径                       | 说明 |
|---|----------------------------|---|
| POST | `/videos`                  | 提交生成任务(异步,202) |
| GET | `/videos/{taskId}`         | 查询任务状态 |
| GET | `/videos`                  | 任务列表(分页) |
| GET | `/videos/{taskId}/content` | 下载产物 |
| GET | `/models`                  | 查询可用模型清单 |
| GET | `/videos/docs`             | 本文档(原始 Markdown) |

## 4. 提交生成任务

```
POST /api/v1/videos
```

### 请求体

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `prompt` | string | ✅ | 提示词,描述画面/镜头/风格 |
| `model` | string | ✅ | 模型标识(全局 id,见 §6 `GET /models`) |
| `images` | string[] | 图生模型必填 | 参考图 URL 列表,后端下载转存 |
| `duration` | int | 视频模型 | 时长(秒),不传默认 8 |
| `ratio` | string | 否 | 画面比例,如 `16:9`、`9:16`、`1:1`,不传默认 `16:9` |
| `megapixels` | number | 否 | 分辨率档位(仅支持该能力的模型) |

### 请求头

| 头 | 必填 | 说明 |
|---|---|---|
| `Authorization` | ✅ | `Bearer sk-...` |
| `Content-Type` | ✅ | `application/json` |
| `Idempotency-Key` | 推荐 | 幂等键(见 §5):同一键重复提交只生成一次 |

### 示例

```bash
curl -X POST https://123.58.111.244:9090/api/v1/videos \
  -H "Authorization: Bearer sk-xxx" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: my-unique-key-001" \
  -d '{
    "prompt": "一只橘猫在窗台晒太阳,镜头缓慢推近,电影感",
    "model": "seedance-fast",
    "duration": 5,
    "ratio": "16:9"
  }'
```

### 响应(202 Accepted)

```json
{
  "taskId": "cgt-20260806110000-xxxxx",
  "status": "PROCESSING",
  "requestId": "req_xxxxxxxx"
}
```

`requestId` 与请求追踪号一致,报障时提供它可查全链路。

## 5. 幂等(重要)

提交请求携带 `Idempotency-Key` 头(自定义字符串):

- 同一 Key **重复提交 → 返回同一个 `taskId`**,不会重复生成、不会重复计费;
- 用于「网络超时后重试」场景,防止双倍扣费;
- Key 建议 8-64 位字母数字,全局唯一(可用 UUID);
- 不带该头也能提交,但无法防重。

## 6. 查询与下载

### 查询状态

```
GET /api/v1/videos/{taskId}
```

```json
{
  "taskId": "cgt-...",
  "status": "PROCESSING",
  "model": "seedance-fast",
  "costAmount": 9.60,
  "videoUrl": "data/videos/xxx.mp4",
  "errorMsg": null
}
```

`status` 取值:`PROCESSING`(生成中)/ `SUCCESS`(完成,`videoUrl` 为后端本地路径)/ `FAILED`(失败,`errorMsg` 为原因)。

> `videoUrl` 是后端内部路径,**不要直接使用**;产物一律通过下载接口获取。

### 下载产物

```
GET /api/v1/videos/{taskId}/content
```

返回文件流(视频 `video/mp4` 或图片 `image/png` 等,按实际类型),凭同一钥匙鉴权:

```bash
curl -s -o result.mp4 https://123.58.111.244:9090/api/v1/videos/{taskId}/content \
  -H "Authorization: Bearer sk-xxx"
```

### 任务列表

```
GET /api/v1/videos?current=1&size=10
```

返回该钥匙的分页任务(按创建时间倒序)。

### 模型清单(建议集成时先查一次)

```
GET /api/v1/models
```

```json
[
  {
    "model": "seedance-fast",
    "label": "Seedance 2.0 Fast",
    "provider": "seedance",
    "outputType": "VIDEO",
    "needImages": false,
    "imageMin": 0,
    "imageMax": 9,
    "ratios": ["16:9", "9:16", "1:1", "4:3", "3:4"],
    "durations": [5, 8, 10, 15],
    "megapixels": [],
    "open": true
  }
]
```

- `open:false` 的模型不可提交(平台未开放);
- `needImages` 为 true 的模型提交时必须带 `images`;
- `outputType`:`VIDEO` / `IMAGE`,决定产物类型与下载接口返回的文件类型;
- `durations`/`ratios`/`megapixels` 为空数组表示该模型不支持该参数。

## 7. 错误码

失败统一返回:

```json
{
  "error": {
    "code": "MODEL_NOT_FOUND",
    "message": "模型不存在: xxx",
    "request_id": "req_xxxxxxxx"
  }
}
```

| HTTP | code | 说明 | 可重试 |
|---|---|---|---|
| 400 | `VALIDATION_ERROR` | 参数缺失/非法(如 prompt 为空、参考图下载失败) | ❌ |
| 400 | `MODEL_NOT_FOUND` | 模型不存在 | ❌ |
| 400 | `RATIO_NOT_SUPPORTED` / `IMAGE_COUNT_INVALID` | 比例/图片数不符合模型能力 | ❌ |
| 401 | `INVALID_API_KEY` | Key 无效 | ❌ |
| 403 | `API_KEY_DISABLED` / `API_KEY_EXPIRED` | Key 被撤销 / 已过期 | ❌ |
| 403 | `MODEL_NOT_OPEN` | 模型未开放 | ❌ |
| 404 | `TASK_NOT_FOUND` | 任务不存在(或不属于该钥匙) | ❌ |
| 409 | `REQUEST_IN_PROGRESS` | 同一幂等键的请求处理中,稍后查询 | ✅ |
| 429 | `RATE_LIMITED` | 触发限流,按 `Retry-After` 头等待 | ✅ 等 Retry-After |
| 503 | `PROVIDER_UNAVAILABLE` | 生成提供方暂时不可用(如节点繁忙) | ✅ |
| 500 | `INTERNAL_ERROR` | 服务内部错误 | ✅ |

**重试建议**:5xx / 429 可安全重试(配合幂等键);4xx 修改参数后再试,重试无意义。

## 8. Webhook 回调(可选)

创建 Key 时配置 `callbackUrl`,任务到达终态时平台主动 POST:

```
POST {callbackUrl}
Content-Type: application/json
X-Signature: <HMAC-SHA256 十六进制签名>
```

Payload:

```json
{
  "task_id": "cgt-...",
  "status": "SUCCESS",
  "output_type": "VIDEO",
  "video_url": "data/videos/xxx.mp4",
  "error": null,
  "cost_amount": 9.60
}
```

### 验签

`X-Signature = HMAC-SHA256(webhookSecret, 原始请求体)`,hex 小写编码。`webhookSecret` 在创建 Key 时由平台生成(可在管理页查看 Key 详情后向管理员索取,或后续支持自管)。

Python 验签示例:

```python
import hmac, hashlib, requests

payload = requests.request("POST", ...)  # 你的回调入口收到的原始 body 字符串
expected = hmac.new(webhook_secret.encode(), payload.encode(), hashlib.sha256).hexdigest()
assert hmac.compare_digest(payload.headers["X-Signature"], expected)
```

### 投递策略

- 同一任务同一终态**只投递一次**(幂等);
- 失败按 30s → 2m → 10m 退避重试,**最多 3 次**;
- 收到回调后,凭 `task_id` 调下载接口取产物;
- 建议回调接口:先验签 → 立即返回 2xx → 异步处理(避免超时重试)。

## 9. 完整调用示例(提交 → 轮询 → 下载)

```bash
KEY="sk-xxx"

# 1. 提交
TASK=$(curl -s -X POST https://123.58.111.244:9090/api/v1/videos \
  -H "Authorization: Bearer $KEY" -H 'Content-Type: application/json' \
  -d '{"prompt":"一只橘猫","model":"seedance-fast","duration":5}' \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["taskId"])')

# 2. 轮询直到终态
for i in $(seq 1 60); do
  STATUS=$(curl -s https://123.58.111.244:9090/api/v1/videos/$TASK -H "Authorization: Bearer $KEY" \
    | python3 -c 'import sys,json;print(json.load(sys.stdin)["status"])')
  echo "第 ${i} 次: $STATUS"
  [ "$STATUS" = "SUCCESS" ] && break
  [ "$STATUS" = "FAILED" ] && break
  sleep 5
done

# 3. 下载
curl -s -o result.mp4 https://your-host:8080/api/v1/videos/$TASK/content -H "Authorization: Bearer $KEY"
```

## 10. 计费

- 按模型定价:云端模型按生成时长计费(秒),自建节点按次计费;
- 费用记在 Key 属主账号名下,可向管理员查询对账;
- **被拒请求(4xx)与限流(429)不计费**;提交成功即按对应模型规则计费(生成失败会退款/不扣费,具体以平台规则为准)。

## 11. 常见问题

| 问题 | 解答 |
|---|---|
| 提交返回 403 MODEL_NOT_OPEN | 该模型未对平台开放,换 `GET /models` 里 `open:true` 的模型 |
| 轮询一直是 PROCESSING | 生成通常需要 30 秒 ~ 数分钟(视频),耐心轮询或等 webhook |
| 下载返回 400「任务尚无产物」 | 任务还未到 SUCCESS,先查状态 |
| 提交偶发 503 | 提供方繁忙,带幂等键重试 |
| 换环境后 Key 失效 | Key 绑定属主与创建环境,向管理员重新申请 |
