# OSS 裸域名图片 URL 修复指南

## 适用现象

浏览器打开图片时看到 OSS XML 错误，例如：

```xml
<Code>NoSuchKey</Code>
<Key>hszs-generate-api.oss-cn-beijing.aliyuncs.com/images/xxx.png</Key>
```

正常的 OSS object key 应是：

```text
images/xxx.png
```

## 根因

线上 `ALIYUN_OSS_DOMAIN` 配置为裸域名：

```text
hszs-generate-api.oss-cn-beijing.aliyuncs.com
```

旧代码会将它直接和对象 key 拼接，生成没有协议的地址：

```text
hszs-generate-api.oss-cn-beijing.aliyuncs.com/images/xxx.png
```

浏览器将其解释成相对 URL，最终请求 OSS 时把域名本身当作对象 key 的一部分，从而出现 `NoSuchKey`。

## 修复步骤

### 1. 修改线上环境变量

推荐显式配置完整 HTTPS URL：

```bash
ALIYUN_OSS_DOMAIN=https://hszs-generate-api.oss-cn-beijing.aliyuncs.com
```

当前代码已经能自动为裸域名补 `https://`，但仍推荐配置完整 URL，便于排查和跨服务复用。

### 2. 部署包含修复的应用版本

修复位于：

```text
OssServiceImpl.resolveBaseDomain()
```

新上传的图片将返回正确的完整 URL。

### 3. 备份并检查历史错误 URL

执行更新前先备份数据库。以下 SQL 只检查可能受影响的记录：

```sql
SELECT id, url
FROM user_asset
WHERE url REGEXP '^[A-Za-z0-9.-]+/images/';

SELECT id, images
FROM video_task
WHERE images LIKE '%hszs-generate-api.oss-cn-beijing.aliyuncs.com/images/%'
   OR images LIKE '%"hszs-generate-api.%';
```

### 4. 修复 `user_asset.url`

以下示例仅适用于错误 URL 的前缀确实是当前 OSS 裸域名。先在副本或测试库验证影响行数：

```sql
UPDATE user_asset
SET url = CONCAT('https://', url)
WHERE url LIKE 'hszs-generate-api.oss-cn-beijing.aliyuncs.com/images/%';
```

验证：

```sql
SELECT id, url
FROM user_asset
WHERE url LIKE 'https://hszs-generate-api.oss-cn-beijing.aliyuncs.com/images/%';
```

### 5. 修复 `video_task.images` JSON 文本

当前 `images` 字段保存 JSON 字符串，因此只替换 JSON 内的裸域名，不改变图片对象 key：

```sql
UPDATE video_task
SET images = REPLACE(
    images,
    '"hszs-generate-api.oss-cn-beijing.aliyuncs.com/images/',
    '"https://hszs-generate-api.oss-cn-beijing.aliyuncs.com/images/'
)
WHERE images LIKE '%"hszs-generate-api.oss-cn-beijing.aliyuncs.com/images/%';
```

验证：

```sql
SELECT id, images
FROM video_task
WHERE images LIKE '%"hszs-generate-api.oss-cn-beijing.aliyuncs.com/images/%';
```

期望结果为 0 行。

## 注意事项

- 将 `hszs-generate-api.oss-cn-beijing.aliyuncs.com` 换成你线上实际配置的 OSS 域名；
- 不要把 `https://` 再加到已经带协议的 URL 上；
- 不要修改 OSS object key，本问题只涉及数据库中的 URL 缺失协议；
- 如果 Bucket 为私有读，修复 URL 协议后仍可能 403；ComfyUI 读取参考图需要可访问的 URL 或改为后端签名下载方案；
- 执行生产 SQL 前先备份并先 `SELECT` 验证命中范围；
- 迁移完成后，随机抽查素材库图片、历史任务详情和流水线引用是否可正常加载。
