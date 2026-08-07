package org.example.seedancegenarate.engine.comfyui;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.http.HttpUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 针对单台 ComfyUI 实例的 HTTP 封装。每个方法都显式传入该实例的 baseUrl，
 * 以保证节点亲和：同一任务的上传 / 提交 / 查询 / 取结果都打到同一台。
 */
@Component
@RequiredArgsConstructor
public class ComfyUiClient {

    private final ObjectMapper objectMapper;

    /** 下载图片字节 */
    public byte[] downloadBytes(String url) {
        return HttpUtil.downloadBytes(url);
    }

    /** 上传图片到 ComfyUI 的 input 目录，返回 LoadImage 可用的文件名 */
    public String uploadImage(String baseUrl, byte[] bytes, String filename, int timeoutMs) throws Exception {
        HttpResponse resp = HttpRequest.post(baseUrl + "/upload/image")
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

    /** 提交工作流，返回 prompt_id */
    public String submitPrompt(String baseUrl, JsonNode workflow, String clientId, int timeoutMs) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("prompt", workflow);
        body.put("client_id", clientId);
        HttpResponse resp = HttpRequest.post(baseUrl + "/prompt")
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
        HttpResponse resp = HttpRequest.get(baseUrl + "/system_stats")
                .timeout(timeoutMs)
                .execute();
        if (!resp.isOk()) {
            throw new RuntimeException("ComfyUI 查询系统状态失败: " + resp.getStatus());
        }
        return objectMapper.readTree(resp.body());
    }

    /** 查询任务历史（含状态与输出）；prompt_id 不在结果里表示仍在排队 / 执行 */
    public JsonNode getHistory(String baseUrl, String promptId, int timeoutMs) throws Exception {
        HttpResponse resp = HttpRequest.get(baseUrl + "/history/" + promptId)
                .timeout(timeoutMs)
                .execute();
        if (!resp.isOk()) {
            throw new RuntimeException("ComfyUI 查询历史失败: " + resp.getStatus() + " " + resp.body());
        }
        return objectMapper.readTree(resp.body());
    }

    /** 队列负载 = 运行中 + 排队中；用于 least-queue 调度，同时兼作健康检查 */
    public int queueLoad(String baseUrl, int timeoutMs) throws Exception {
        HttpResponse resp = HttpRequest.get(baseUrl + "/queue")
                .timeout(timeoutMs)
                .execute();
        if (!resp.isOk()) {
            throw new RuntimeException("ComfyUI 查询队列失败: " + resp.getStatus());
        }
        JsonNode n = objectMapper.readTree(resp.body());
        return n.path("queue_running").size() + n.path("queue_pending").size();
    }

    /** 构造结果文件的下载地址（/view），指向具体节点 */
    public String buildViewUrl(String baseUrl, String filename, String subfolder, String type) {
        return baseUrl + "/view?filename=" + enc(filename)
                + "&subfolder=" + enc(subfolder)
                + "&type=" + enc(type);
    }

    private String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }
}
