package org.example.seedancegenarate.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.example.seedancegenarate.dto.ApiTaskView;
import org.example.seedancegenarate.dto.ApiTaskPageView;
import org.example.seedancegenarate.dto.ApiVideoCreateRequest;
import org.example.seedancegenarate.dto.ApiVideoCreateResponse;
import org.example.seedancegenarate.config.OssConfig;
import org.example.seedancegenarate.entity.ApiKey;
import org.example.seedancegenarate.entity.VideoTask;
import org.example.seedancegenarate.exception.ApiException;
import org.example.seedancegenarate.service.ApiDocService;
import org.example.seedancegenarate.service.ArtifactStorage;
import org.example.seedancegenarate.service.ApiVideoService;
import org.example.seedancegenarate.service.ContentModerationPolicy;
import org.example.seedancegenarate.service.Impl.ApiVideoServiceImpl;
import org.example.seedancegenarate.service.VideoTaskService;
import org.example.seedancegenarate.util.IpUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;

/**
 * 【对外 API - 核心视频/图片生成与产物控制器】
 * <p>
 * 业务定位：
 * 面向持 API Key（sk- 开头）调用的外部商业客户，提供完整的生成生命周期管理：
 * 1. 提交生成任务（POST）：返回 202 Accepted 与全局唯一的业务任务 ID（biz_task_id），支持 Idempotency-Key 幂等防重放；
 * 2. 状态轮询（GET /{taskId}）：查询任务最新状态（PROCESSING / SUCCESS / FAILED）；
 * 3. 任务分页列表（GET）：查看当前 Key 提交的历史任务；
 * 4. 产物下载/预览（GET /{taskId}/content）：成功后重定向至阿里云 OSS 预签名下载地址或流式读取历史产物。
 * <p>
 * 安全机制：
 * 1. ApiKeyInterceptor 鉴权：必须持有合法的 API Key；
 * 2. 内容安全审核拦截（ContentModerationPolicy）：命中违规的内容脱敏或拒绝提供下载；
 * 3. 防路径穿越攻击：本地历史文件下载对文件名和相对路径进行严格正则与真实路径校验（LinkOption.NOFOLLOW_LINKS）。
 * <p>
 * 访问前缀：/api/v1/videos
 */
@RestController
@RequestMapping("/api/v1/videos")
@RequiredArgsConstructor
public class ApiVideoController {

    /** 对外生成核心门面服务：处理 API 层的任务创建编排、调用日志记录 */
    private final ApiVideoService apiVideoService;

    /** 视频任务持久化服务：对 video_task 数据库表的操作封装 */
    private final VideoTaskService videoTaskService;

    /** 接入文档服务：提供 Markdown 格式的开发接入文档内容 */
    private final ApiDocService apiDocService;

    /** 对象存储管理：负责签发带签名的短期安全下载 URL（阿里云 OSS） */
    private final ArtifactStorage artifactStorage;

    /** 阿里云 OSS 配置：包含签名 URL 的过期时长 TTL 等配置项 */
    private final OssConfig ossConfig;

    /** 产物过期策略：计算产物何时到期并打上过期标记（如 outputs/ 目录 48 小时自动清理） */
    private final org.example.seedancegenarate.service.ArtifactExpiryPolicy artifactExpiryPolicy;

    /** 内容合规风控策略：对敏感/违规任务进行脱敏（Redact）或拦截（Block） */
    private final ContentModerationPolicy contentModerationPolicy;

    /** 本地旧版产物存储根目录（用于历史生成任务的向下兼容） */
    private Path localArtifactRoot = Paths.get("data/videos");

    /**
     * 安全辅助方法：从当前 HTTP 请求上下文中提取 ApiKeyInterceptor 注入的 API Key 实体
     *
     * @param request HTTP 请求
     * @return 当前调用凭证 ApiKey
     * @throws ApiException 401 Unauthorized（无效的 API Key）
     */
    private static ApiKey currentKey(HttpServletRequest request) {
        Object key = request.getAttribute("api_key");
        if (!(key instanceof ApiKey apiKey)) {
            throw ApiException.invalidApiKey();
        }
        return apiKey;
    }

    /**
     * 获取接入文档内容
     * <p>
     * 接口路径：GET /api/v1/videos/docs
     *
     * @return Markdown 格式的接入开发文档
     */
    @GetMapping("/docs")
    public String docs() {
        return apiDocService.content();
    }

    /**
     * 【提交生成任务（异步）】
     * <p>
     * 接口路径：POST /api/v1/videos
     * 设计哲学：
     * 1. 响应状态码为 202 Accepted，表示请求已被接受并加入后台异步处理队列，此时任务尚未完成；
     * 2. 幂等键（Idempotency-Key）：如果客户端发生网络超时重试，只要携带相同的 Header，
     *    后端识别到相同幂等键会直接返回第一次创建的任务，绝不会重复扣除用户算力；
     * 3. 立即返回稳定 taskId，客户端后续通过轮询本控制器或接收 Webhook 异步回调追踪结果。
     *
     * @param request 包含 prompt、model、参考图片/视频/音频列表、时长、画面比例等
     * @param idempotencyKey 客户端可选传入的幂等防重键（请求头 Idempotency-Key）
     * @param servletRequest 原生 HTTP 请求对象，用于解析客户端 IP 与 User-Agent
     * @return 包含业务任务 ID、初始状态（PROCESSING）以及 requestId 的 202 响应体
     */
    @PostMapping
    public ResponseEntity<ApiVideoCreateResponse> create(
            @RequestBody ApiVideoCreateRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest servletRequest
    ) {
        // 1. 提取当前 API Key
        ApiKey key = currentKey(servletRequest);

        // 2. 核心参数非空校验：提示词 prompt 不能为空
        if (request == null || request.prompt() == null || request.prompt().isBlank()) {
            throw ApiException.validation("prompt 不能为空");
        }

        // 3. 幂等键 / 请求唯一标识解析：若客户端未传则系统自动生成安全 UUID
        String requestId;
        if (idempotencyKey == null) {
            requestId = ApiVideoServiceImpl.generateRequestId();
        } else {
            requestId = idempotencyKey.trim();
            // 原始头先查控制字符，不能让 trim 吞掉非法输入后变成另一个合法键。
            if (requestId.isBlank() || requestId.length() > 64
                    || idempotencyKey.codePoints().anyMatch(c -> Character.isISOControl(c)
                    || Character.getType(c) == Character.FORMAT || c == 0x2028 || c == 0x2029)) {
                throw ApiException.validation("Idempotency-Key 必须为1至64个字符，且不能包含控制字符");
            }
        }

        // 4. 调用对外生成服务完成全套创建流程（两阶段调用日志记录、资金预冻结、落库 video_task、生成异步作业）
        VideoTask task = apiVideoService.create(new ApiVideoService.CreateContext(
                key, requestId, IpUtils.getClientIp(servletRequest),
                servletRequest.getHeader("User-Agent"),
                request.prompt().trim(), request.model(), request.images(), request.videos(), request.audios(),
                request.duration(), request.ratio(), request.megapixels(), request.resolution()));

        // 5. 立即返回 202 Accepted 状态码与任务 ID
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new ApiVideoCreateResponse(task.businessTaskId(), task.getStatus(), requestId));
    }

    /**
     * 【查询单任务生成状态与产物信息】
     * <p>
     * 接口路径：GET /api/v1/videos/{taskId}
     * 状态流转：PROCESSING（排队/生成中） ➔ SUCCESS（生成成功，带视频播放地址） / FAILED（生成失败，带错误原因）
     *
     * @param taskId 业务任务 ID
     * @param servletRequest 用于获取当前调用者的 API Key
     * @return ApiTaskView
     */
    @GetMapping("/{taskId}")
    public ApiTaskView get(@PathVariable String taskId, HttpServletRequest servletRequest) {
        // 1. 查询属于当前 API Key 的任务，若查不到或属于别人则抛 404
        // 2. 盖上产物是否过期的时间戳印记（artifactExpiryPolicy.stamp）
        // 3. 若命中违规，执行安全脱敏（contentModerationPolicy.redact）
        // 4. 转化为对外视图 DTO 返回
        return ApiTaskView.from(contentModerationPolicy.redact(
                artifactExpiryPolicy.stamp(findTask(currentKey(servletRequest).getId(), taskId))));
    }

    /**
     * 【分页查询当前 API Key 提交的任务列表】
     * <p>
     * 接口路径：GET /api/v1/videos?current=1&size=10
     *
     * @param current 当前页码，默认第 1 页
     * @param size 每页条数，默认 10 条，上限保护为 100 条
     * @return ApiTaskPageView 分页结果包装对象
     */
    @GetMapping
    public ApiTaskPageView list(
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "10") long size,
            HttpServletRequest servletRequest
    ) {
        // 约束页码和每页大小在安全区间内，防止大分页打垮内存
        long pageCurrent = Math.max(current, 1L);
        long pageSize = Math.min(Math.max(size, 1L), 100L);

        // 执行数据库分页查询，强制按照 apiKeyId 严格隔离，按 ID 倒序排列
        Page<VideoTask> page = artifactExpiryPolicy.stampAll(videoTaskService.page(
                new Page<>(pageCurrent, pageSize),
                Wrappers.<VideoTask>lambdaQuery()
                        .eq(VideoTask::getApiKeyId, currentKey(servletRequest).getId())
                        .orderByDesc(VideoTask::getId)));

        // 统一对当前页所有数据执行内容安全脱敏
        contentModerationPolicy.redactAll(page);

        // 组装分页视图并返回
        return new ApiTaskPageView(page.getRecords().stream().map(ApiTaskView::from).toList(),
                page.getTotal(), page.getSize(), page.getCurrent(), page.getPages());
    }

    /**
     * 【下载 / 查看生成产物媒体流】
     * <p>
     * 接口路径：GET /api/v1/videos/{taskId}/content
     * 执行逻辑：
     * 1. 安全合规：命中封禁的内容直接拒绝下载；
     * 2. 状态检查：确认任务产物存在且未被生命周期规则自动清理过期；
     * 3. 云上产物（OSS）：302 重定向到带防伪签名的短期安全下载 URL；
     * 4. 本地旧产物：执行防路径穿越校验后以流式输出传输给客户端，并根据文件后缀推导准确的 Content-Type。
     */
    @GetMapping("/{taskId}/content")
    public void content(@PathVariable String taskId, HttpServletRequest servletRequest,
                        HttpServletResponse response) throws Exception {
        VideoTask task = findTask(currentKey(servletRequest).getId(), taskId);

        // 1. 内容审核拦截：违规内容直接阻断
        if (contentModerationPolicy.isBlocked(task)) {
            throw ApiException.contentBlocked(contentModerationPolicy.blockedMessage(task));
        }

        // 2. 校验产物是否存在
        if (task.getVideoUrl() == null || task.getVideoUrl().isBlank()) {
            throw ApiException.validation("任务尚无产物");
        }

        // 3. 过期必须先判：createSignedGetUrl 是纯本地计算、不校验对象是否存在，
        // 放行下去只会 302 到一个必然 404 的地址，调用方拿不到任何可判断的信号。
        if (artifactExpiryPolicy.isExpired(task)) {
            throw ApiException.artifactExpired(artifactExpiryPolicy.expiredMessage());
        }

        // 4. 云原生对象存储分支（现代模式）：签发 OSS 预签名地址并以 302 临时重定向给客户端
        if (hasOssArtifact(task)) {
            response.sendRedirect(artifactStorage.createSignedGetUrl(
                    task.getArtifactKey(), Duration.ofSeconds(ossConfig.getSignedUrlTtlSeconds())));
            return;
        }

        // 5. 本地旧版文件分支（历史兼容）：解析本地绝对安全路径
        Path path = localArtifactPath(task.getVideoUrl());

        // 打开文件时也不跟随链接，避免校验后替换最终文件为符号链接。
        try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            response.setContentType(contentTypeOf(path.getFileName().toString()));
            input.transferTo(response.getOutputStream());
        }
    }

    /**
     * 内部安全辅助：解析并校验本地历史产物的文件绝对路径
     * 严格防范 ../ 目录穿越漏洞（Path Traversal Attack）和软链接欺骗
     */
    private Path localArtifactPath(String stored) throws IOException {
        String fileName = stored.startsWith("data/videos/")
                ? stored.substring("data/videos/".length())
                : stored;

        // 文件名白名单正则校验：只允许字母、数字、点、下划线、减号
        if (fileName.length() > 255 || !fileName.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
            throw ApiException.validation("产物文件标识无效");
        }

        try {
            Path root = localArtifactRoot.toRealPath();
            Path candidate = root.resolve(fileName).normalize();

            // 必须在 root 目录树之下且为常规文件，不跟随软链接
            if (!candidate.startsWith(root) || !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
                throw ApiException.validation("产物文件不可用");
            }
            Path real = candidate.toRealPath();
            if (!real.startsWith(root)) {
                throw ApiException.validation("产物文件不可用");
            }
            return candidate;
        } catch (NoSuchFileException e) {
            throw ApiException.validation("产物文件不存在");
        }
    }

    /**
     * 判断任务的产物是否存放在阿里云 OSS 对象存储上
     */
    private boolean hasOssArtifact(VideoTask task) {
        return "OSS".equals(task.getArtifactStorageType())
                && task.getArtifactKey() != null && !task.getArtifactKey().isBlank();
    }

    /**
     * 根据 apiKeyId 与 taskId 查询任务，确保数据隔离
     * 兼容查询 biz_task_id 与历史旧版 task_id
     */
    private VideoTask findTask(Long apiKeyId, String taskId) {
        VideoTask task = videoTaskService.getOne(Wrappers.<VideoTask>lambdaQuery()
                .and(w -> w.eq(VideoTask::getBizTaskId, taskId)
                        .or()
                        .eq(VideoTask::getTaskId, taskId))
                .eq(VideoTask::getApiKeyId, apiKeyId), false);
        if (task == null) {
            throw ApiException.taskNotFound();
        }
        return task;
    }

    /**
     * 按产物扩展名推断 MIME 类型，确保浏览器能够正确播放或渲染，未知类型不冒充可播放视频。
     */
    private String contentTypeOf(String fileName) {
        String lower = fileName.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webm")) return "video/webm";
        if (lower.endsWith(".mov")) return "video/quicktime";
        if (lower.endsWith(".mkv")) return "video/x-matroska";
        if (lower.endsWith(".mp4")) return "video/mp4";
        if (lower.endsWith(".mp3")) return "audio/mpeg";
        if (lower.endsWith(".m4a")) return "audio/mp4";
        if (lower.endsWith(".aac")) return "audio/aac";
        if (lower.endsWith(".wav")) return "audio/wav";
        if (lower.endsWith(".ogg")) return "audio/ogg";
        if (lower.endsWith(".flac")) return "audio/flac";
        return "application/octet-stream";
    }
}

