package org.example.seedancegenarate.engine.comfyui;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 针对单台 ComfyUI 实例的 HTTP 封装。每个方法都显式传入该实例的 baseUrl，
 * 以保证节点亲和：同一任务的上传 / 提交 / 查询 / 取结果都打到同一台。
 * 所有请求统一携带 {@code X-Comfy-Token}（nginx 入口校验，未配置时不影响）。
 */
@Component
@RequiredArgsConstructor
public class ComfyUiClient {

    private final ObjectMapper objectMapper;
    private final ComfyUiProperties properties;

    /** 下载字节（参考素材下载；nginx 入口同样要求 token）。
     *  读超时是 SO_TIMEOUT（两次收包之间的静默上限），大文件只要在流动就不会误杀；
     *  不设超时则对端挂起时线程无限阻塞。 */
    public byte[] downloadBytes(String url) {
        return withAuth(HttpRequest.get(url))
                .setConnectionTimeout(properties.getConnectTimeoutMs())
                .setReadTimeout(properties.getReadTimeoutMs())
                .execute()
                .bodyBytes();
    }

    /** 上传文件到 ComfyUI 的 input 目录，返回 LoadImage / LoadAudio / XB_VideoLoader 可用的文件名。
     *  ComfyUI 的 /upload/image 端点接受任意文件类型并存入 input/，故图片 / 视频 / 音频复用同一条路径。 */
    public String uploadImage(String baseUrl, byte[] bytes, String filename, int timeoutMs) throws Exception {
        HttpResponse resp = withAuth(HttpRequest.post(baseUrl + "/upload/image"))
                .form("image", bytes, filename)
                .form("overwrite", "true")
                .timeout(timeoutMs)
                .execute();
        if (!resp.isOk()) {
            throw new RuntimeException("ComfyUI 上传图片失败: " + resp.getStatus() + " " + resp.body());
        }
        JsonNode n = objectMapper.readTree(resp.body());
        String name = n.path("name").asText(filename);
        String subfolder = n.path("subfolder").asText("");
        return subfolder.isEmpty() ? name : subfolder + "/" + name;
    }

    /** 提交工作流，返回 prompt_id；webhookUrl 非空时事件驱动（完成/失败回调） */
    public String submitPrompt(String baseUrl, JsonNode workflow, String clientId,
                               String webhookUrl, int timeoutMs) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("prompt", workflow);
        body.put("client_id", clientId);
        if (webhookUrl != null && !webhookUrl.isBlank()) {
            body.put("webhook_url", webhookUrl);
        }
        HttpResponse resp = withAuth(HttpRequest.post(baseUrl + "/prompt"))
                .body(objectMapper.writeValueAsString(body))
                .timeout(timeoutMs)
                .execute();
        if (!resp.isOk()) {
            throw new RuntimeException("ComfyUI 提交失败: " + resp.getStatus() + " " + resp.body());
        }
        JsonNode n = objectMapper.readTree(resp.body());
        JsonNode errors = n.path("node_errors");
        if (errors.isObject() && errors.size() > 0) {
            throw new RuntimeException("ComfyUI 工作流校验失败 node_errors: " + errors);
        }
        String promptId = n.path("prompt_id").asText("");
        if (promptId.isEmpty()) {
            throw new RuntimeException("ComfyUI 未返回 prompt_id: " + resp.body());
        }
        return promptId;
    }

    /** 查询节点系统状态（GPU 型号 / 显存占用），供管理端健康检测 */
    public JsonNode getSystemStats(String baseUrl, int timeoutMs) throws Exception {
        HttpResponse resp = withAuth(HttpRequest.get(baseUrl + "/system_stats"))
                .timeout(timeoutMs)
                .execute();
        if (!resp.isOk()) {
            throw new RuntimeException("ComfyUI 查询系统状态失败: " + resp.getStatus());
        }
        return objectMapper.readTree(resp.body());
    }

    /**
     * 这台节点装了哪些 node type。响应的<b>顶层 key 就是 node type 名字</b>，
     * 每个值是该节点的完整参数定义 —— 载荷是 MB 级，所以只能慢探（默认 60 秒一轮），
     * 且必须用 readTimeoutMs 而不是 statusTimeoutMs。
     * <p>
     * 这是判断「这台能不能跑某个工作流」<b>唯一可靠</b>的依据：文件列表对得上不代表能跑
     * （插件目录齐了但少个 pip 依赖，import 失败，node type 就是不出现），
     * 而 node type 出现了就一定能跑。
     */
    public JsonNode getObjectInfo(String baseUrl, int timeoutMs) throws Exception {
        HttpResponse resp = withAuth(HttpRequest.get(baseUrl + "/object_info"))
                .timeout(timeoutMs)
                .execute();
        if (!resp.isOk()) {
            throw new RuntimeException("ComfyUI 查询节点类型失败: " + resp.getStatus());
        }
        return objectMapper.readTree(resp.body());
    }

    /** 查询任务历史（含状态与输出）；prompt_id 不在结果里表示仍在排队 / 执行 */
    public JsonNode getHistory(String baseUrl, String promptId, int timeoutMs) throws Exception {
        HttpResponse resp = withAuth(HttpRequest.get(baseUrl + "/history/" + promptId))
                .timeout(timeoutMs)
                .execute();
        if (!resp.isOk()) {
            throw new RuntimeException("ComfyUI 查询历史失败: " + resp.getStatus() + " " + resp.body());
        }
        return objectMapper.readTree(resp.body());
    }

    /** 队列负载 = 运行中 + 排队中；管理端健康检测用（要顺带拿到延迟与错误明细） */
    public int queueLoad(String baseUrl, int timeoutMs) throws Exception {
        JsonNode n = getQueue(baseUrl, timeoutMs);
        return n.path("queue_running").size() + n.path("queue_pending").size();
    }

    /**
     * 队列深度，来自 {@code GET /prompt} —— <b>后台探测器专用</b>。
     * <p>
     * 和 {@link #queueLoad} 是同一个数字，但载荷差几个数量级：{@code /prompt} 的响应是
     * {@code {"exec_info":{"queue_remaining":0}}}（约 37 字节），而 {@code /queue} 带的是
     * 队列中<b>每个 prompt 的完整工作流 JSON</b>（可能几 MB）。
     * 3 秒一轮 × N 个节点，这个差别就是「几乎免费」和「持续烧带宽」的差别。
     */
    public int queueRemaining(String baseUrl, int timeoutMs) throws Exception {
        HttpResponse resp = withAuth(HttpRequest.get(baseUrl + "/prompt"))
                .timeout(timeoutMs)
                .execute();
        if (!resp.isOk()) {
            throw new RuntimeException("ComfyUI 查询队列深度失败: " + resp.getStatus());
        }
        return objectMapper.readTree(resp.body()).path("exec_info").path("queue_remaining").asInt(0);
    }

    /**
     * 带短缓存的队列查询，<b>只给轮询/丢失判定用</b>。
     * <p>
     * {@code /queue} 的响应里带着队列中<b>每个 prompt 的完整工作流 JSON</b>，可能几 MB。
     * 丢失判定会对每个 history 为空的任务查一次队列，同一轮里同节点上的多条任务拿到的
     * 必然是同一份 —— 各取一遍纯属浪费带宽。
     * <p>
     * 调度不用这个，也不用它的无缓存版本 —— 选节点读的是 {@link ComfyUiFleet} 的内存快照，
     * 一次 HTTP 都不发（D-026，2026-08-28 修订）。
     */
    public JsonNode getQueueCached(String baseUrl, int timeoutMs) throws Exception {
        long ttl = Math.max(properties.getQueueCacheMs(), 0);
        if (ttl == 0) {
            return getQueue(baseUrl, timeoutMs);
        }
        long now = System.nanoTime();
        CachedQueue cached = queueCache.get(baseUrl);
        if (cached != null && now - cached.fetchedAtNanos < ttl * 1_000_000L) {
            return cached.queue;
        }
        JsonNode fresh = getQueue(baseUrl, timeoutMs);
        // 并发 miss 时可能多取几次，结果一致，不值得为它上锁
        queueCache.put(baseUrl, new CachedQueue(fresh, now));
        return fresh;
    }

    private record CachedQueue(JsonNode queue, long fetchedAtNanos) {
    }

    private final java.util.Map<String, CachedQueue> queueCache = new java.util.concurrent.ConcurrentHashMap<>();

    /** 完整队列（running + pending 的 prompt_id 列表），实时、不走缓存；调度与 ETA 用 */
    public JsonNode getQueue(String baseUrl, int timeoutMs) throws Exception {
        HttpResponse resp = withAuth(HttpRequest.get(baseUrl + "/queue"))
                .timeout(timeoutMs)
                .execute();
        if (!resp.isOk()) {
            throw new RuntimeException("ComfyUI 查询队列失败: " + resp.getStatus());
        }
        return objectMapper.readTree(resp.body());
    }

    /** 构造结果文件的下载地址（/view），指向具体节点；下载方需自行携带 X-Comfy-Token */
    public String buildViewUrl(String baseUrl, String filename, String subfolder, String type) {
        return baseUrl + "/view?filename=" + enc(filename)
                + "&subfolder=" + enc(subfolder)
                + "&type=" + enc(type);
    }

    private HttpRequest withAuth(HttpRequest request) {
        String token = properties.getAccessToken();
        if (StringUtils.hasText(token)) {
            request.header("X-Comfy-Token", token);
        }
        return request;
    }

    private String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }
}
