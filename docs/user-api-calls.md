# 用户 API 调用记录与汇总

供登录用户的 API 控制台使用，沿用登录令牌 `Authorization: Bearer <login-token>`。
成功响应沿用 `{ "code": 200, "message": "success", "data": ... }`。
查询范围始终是当前登录账号；管理员在这两个入口也只能查自己。客户端无需传 userId。

## 接口与筛选

```http
GET /api/user/api-calls
GET /api/user/api-calls/summary
```

两个接口的筛选参数相同，条件同时满足（AND）：

| 参数 | 类型 | 语义 |
| --- | --- | --- |
| apiKeyId | 正整数 | 指定 Key；不存在或属于别人的 Key 返回空结果 |
| model | string | 模型标识，精确匹配，最多64字符 |
| provider | string | 提供方标识，精确匹配，最多32字符 |
| status | string | RECEIVED / SUCCESS / FAILED / REJECTED |
| errorCode | string | 错误码，精确匹配，最多32字符 |
| from | ISO 本地日期时间 | 按 createTime 筛选，包含下界 |
| to | ISO 本地日期时间 | 按 createTime 筛选，不包含上界 |

时间以 Asia/Shanghai 本地时间解释，例如 `2026-09-15T00:00:00`；不传范围即查全部历史。
查询 9 月 15 日使用 `from=2026-09-15T00:00:00&to=2026-09-16T00:00:00`。
这些参数不接受 UTC 的 Z 或时区偏移形式，前端需要先转换为上海本地时间。
同时提供 from 和 to 时，from 必须小于 to；空时间字符串不合法，未筛选时请省略参数。
时间年份限1000–9999，允许最多9位小数秒。model/provider/errorCode 不接受空白值或控制字符。

列表另支持 `current`（默认1，正整数）、`size`（默认20，1–100），按日志 id 倒序。
current 和 size 使用64位有符号整数，计算 `(current - 1) * size` 溢出也返回400。
返回分页对象含 `records/total/current/size/pages`。
非法参数返回 HTTP 400 + Result code 400，未登录返回 HTTP 401 + Result code 401。

## 响应字段

`data.records[]` 是以下白名单，不返回原始 errorMsg、IP、User-Agent、请求指纹或密钥秘密。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | integer | 调用日志ID |
| requestId | string | 请求追踪标识 |
| taskId | string/null | 业务任务ID，未创建任务时为空 |
| apiKeyId | integer | 日志关联的Key ID |
| apiKeyName / keyPrefix | string/null | 同属主的Key名称/掩码前缀，关联不存在时为空 |
| model / provider | string/null | 模型/提供方 |
| status | string | 调用日志状态 |
| httpCode / errorCode | integer/null、string/null | 日志中的HTTP状态码/错误码，可能未记录 |
| costAmount | number/null | 日志中的金额；未记录时为空 |
| currency | string | 固定CNY |
| createTime / updateTime | string/null | 上海本地 ISO 日期时间 |
| queuedMs / generateMs / totalMs | integer/null | 日志中的毫秒耗时，未记录时为空 |

汇总 `data` 示例（示例数据）：

```json
{
  "total": 4,
  "received": 1,
  "success": 1,
  "failed": 1,
  "rejected": 1,
  "totalCost": 1.25,
  "currency": "CNY",
  "byErrorCode": [{ "errorCode": "INVALID_REQUEST", "count": 1 }]
}
```

`byErrorCode` 统计所有匹配记录中的非空错误码，按次数倒序、错误码升序。
空结果：列表 records 为[]、total为0；汇总所有计数与金额为0、byErrorCode为[]。
汇总不受分页参数影响。

## 统计口径

来源是 `api_call_log`，当前主要记录外部视频生成提交及其处理结果，并非 HTTP 访问日志。
不能把调用次数理解为包括模型查询、任务轮询、下载等在内的全部 HTTP 请求数。
`RECEIVED` 表示已记录、尚未终结的调用；其余状态为成功、失败、拒绝。
`totalCost` 汇总匹配日志的 `costAmount`，NULL 按0，币种 CNY；不是 Token 数、冻结余额或钱包流水余额。
Key 被撤销或关联 Key 缺失后，历史调用仍保留。Key 名称/前缀是可用时的当前元数据。
汇总在数据库执行，不将全部历史记录加载到应用内存。
列表和汇总是独立请求，任务在两次请求之间更新时，返回的数据可能略有差异。

## 示例

```bash
curl --get 'http://localhost:8080/api/user/api-calls' \
  -H 'Authorization: Bearer <login-token>' \
  --data-urlencode 'current=1' \
  --data-urlencode 'size=20' \
  --data-urlencode 'from=2026-09-15T00:00:00' \
  --data-urlencode 'to=2026-09-16T00:00:00'

curl --get 'http://localhost:8080/api/user/api-calls/summary' \
  -H 'Authorization: Bearer <login-token>' \
  --data-urlencode 'from=2026-09-15T00:00:00' \
  --data-urlencode 'to=2026-09-16T00:00:00'
```

使用项目现有 Vue `request` 封装时传 `/user/api-calls` 或 `/user/api-calls/summary`，其 baseURL 已包含 `/api`。
