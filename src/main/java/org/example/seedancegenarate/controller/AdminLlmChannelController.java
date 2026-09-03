package org.example.seedancegenarate.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.example.seedancegenarate.context.UserContext;
import org.example.seedancegenarate.dto.LlmChannelView;
import org.example.seedancegenarate.entity.LlmChannel;
import org.example.seedancegenarate.entity.Result;
import org.example.seedancegenarate.mapper.LlmChannelMapper;
import org.example.seedancegenarate.service.PromptContext;
import org.example.seedancegenarate.service.PromptOptimizeService;
import org.example.seedancegenarate.service.llm.LlmChannelException;
import org.example.seedancegenarate.service.llm.LlmChannelRegistry;
import org.example.seedancegenarate.service.llm.LlmChannelSpec;
import org.example.seedancegenarate.service.llm.LlmChatResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.net.URI;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 管理端 LLM 通道运维：看清单、加通道、试跑、启停、调参、归档。
 *
 * <h3>和节点管理同形，两处不同</h3>
 * <ul>
 *   <li><b>有密钥</b>：返回类型 {@link LlmChannelView} 上没有明文字段；PATCH 的 apiKey 传空 = 保留原值</li>
 *   <li><b>有试跑</b>：换 LLM 不会报错，只会让输出悄悄变差，测试测不出来——切换前必须用固定样例肉眼对比</li>
 * </ul>
 *
 * <h3>没有删除接口</h3>
 * {@code prompt_token_usage.llm_channel} 记的是通道名；删了行，「上个月那批失败是不是都在同一家」就查不了。
 * 归档拿到的是「不参与路由、列表里看不见」这两个真实需求。
 */
@RestController
@RequestMapping("/api/admin/llm-channels")
@RequiredArgsConstructor
public class AdminLlmChannelController {

    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");
    private static final BigDecimal MAX_TEMPERATURE = new BigDecimal("2.00");
    private static final int MAX_TOKENS_CEILING = 32_768;
    /** 试跑用的固定样例：短、有画面、能看出模板有没有被遵守 */
    static final String TRIAL_PROMPT = "一只橘猫在雨后的窗台上打盹，傍晚的光";

    private final LlmChannelMapper llmChannelMapper;
    private final LlmChannelRegistry llmChannelRegistry;
    private final PromptOptimizeService promptOptimizeService;

    /** 通道清单（密钥脱敏）。默认不含归档 */
    @GetMapping
    public Result<List<LlmChannelView>> list(@RequestParam(defaultValue = "false") boolean includeArchived) {
        requireAdmin();
        List<LlmChannelSpec> all = llmChannelRegistry.channels();
        return Result.success(all.stream()
                .filter(c -> includeArchived || !c.archived())
                .map(LlmChannelView::of)
                .toList());
    }

    /**
     * 加一条通道。<b>{@code enabled} 强制 false，请求体里带了也不认。</b>
     * 正确流程：加进来 → 试跑看输出 → 再开。让调用方能覆盖这个默认，等于允许一条没验证过的通道立刻服务真实用户。
     */
    @PostMapping
    public Result<Void> create(@RequestBody LlmChannelUpsertRequest request) {
        requireAdmin();
        String name = validName(request.getName());
        if (llmChannelMapper.selectById(name) != null) {
            throw new IllegalArgumentException("通道名已存在: " + name);
        }
        LlmChannel row = new LlmChannel();
        row.setName(name);
        row.setBaseUrl(validBaseUrl(request.getBaseUrl()));
        row.setApiKey(requiredText(request.getApiKey(), "密钥", 255));
        row.setModel(requiredText(request.getModel(), "模型", 128));
        row.setTemperature(validTemperature(request.getTemperature()));
        row.setMaxTokens(request.getMaxTokens() == null ? 1500 : validMaxTokens(request.getMaxTokens()));
        row.setTokenParam(validTokenParam(request.getTokenParam()));
        row.setTimeoutMs(request.getTimeoutMs() == null ? 100_000 : validTimeout(request.getTimeoutMs()));
        row.setPriority(request.getPriority() == null ? 100 : validPriority(request.getPriority()));
        row.setEnabled(false); // 刻意不读 request.enabled
        row.setArchived(false);
        row.setRemark(validRemark(request.getRemark()));
        llmChannelMapper.insert(row);
        llmChannelRegistry.invalidate();
        return Result.success(null);
    }

    /** 改参数 / 开关 / 归档。只改传了的字段；apiKey 传空 = 保留 */
    @PatchMapping("/{name}")
    public Result<Void> update(@PathVariable String name, @RequestBody LlmChannelUpsertRequest request) {
        requireAdmin();
        LlmChannel existing = llmChannelMapper.selectById(name);
        if (existing == null) {
            throw new IllegalArgumentException("通道不存在: " + name);
        }
        if (Boolean.TRUE.equals(request.getEnabled())
                && Boolean.TRUE.equals(existing.getArchived())
                && !Boolean.FALSE.equals(request.getArchived())) {
            throw new IllegalArgumentException("归档通道必须先取消归档才能启用");
        }
        var update = Wrappers.<LlmChannel>lambdaUpdate().eq(LlmChannel::getName, name);
        boolean changed = false;
        if (request.getBaseUrl() != null) {
            update.set(LlmChannel::getBaseUrl, validBaseUrl(request.getBaseUrl()));
            changed = true;
        }
        if (request.getApiKey() != null && !request.getApiKey().isBlank()) {
            // 空串/空白 = 保留原值。前端编辑框里放的是脱敏形态，原样提交回来不能把真 key 覆盖成 "sk-a7f••••••1b"
            String key = request.getApiKey().trim();
            if (key.contains("•")) {
                throw new IllegalArgumentException("密钥看起来是脱敏后的显示值，请填完整密钥或留空保留原值");
            }
            update.set(LlmChannel::getApiKey, requiredText(key, "密钥", 255));
            changed = true;
        }
        if (request.getModel() != null) {
            update.set(LlmChannel::getModel, requiredText(request.getModel(), "模型", 128));
            changed = true;
        }
        if (Boolean.TRUE.equals(request.getClearTemperature())) {
            update.set(LlmChannel::getTemperature, null);
            changed = true;
        } else if (request.getTemperature() != null) {
            update.set(LlmChannel::getTemperature, validTemperature(request.getTemperature()));
            changed = true;
        }
        if (request.getMaxTokens() != null) {
            update.set(LlmChannel::getMaxTokens, validMaxTokens(request.getMaxTokens()));
            changed = true;
        }
        if (request.getTokenParam() != null) {
            update.set(LlmChannel::getTokenParam, validTokenParam(request.getTokenParam()));
            changed = true;
        }
        if (request.getTimeoutMs() != null) {
            update.set(LlmChannel::getTimeoutMs, validTimeout(request.getTimeoutMs()));
            changed = true;
        }
        if (request.getPriority() != null) {
            update.set(LlmChannel::getPriority, validPriority(request.getPriority()));
            changed = true;
        }
        if (request.getRemark() != null) {
            update.set(LlmChannel::getRemark, validRemark(request.getRemark()));
            changed = true;
        }
        if (request.getEnabled() != null) {
            update.set(LlmChannel::getEnabled, request.getEnabled());
            changed = true;
        }
        if (request.getArchived() != null) {
            update.set(LlmChannel::getArchived, request.getArchived());
            changed = true;
            if (Boolean.TRUE.equals(request.getArchived())) {
                // 归档必然连带停用：一条「已归档但还在路由里」的通道在列表里看不见却仍在服务，最难发现
                update.set(LlmChannel::getEnabled, false);
            }
        }
        if (!changed) {
            throw new IllegalArgumentException("至少提供一个要修改的字段");
        }
        llmChannelMapper.update(null, update);
        llmChannelRegistry.invalidate();
        return Result.success(null);
    }

    /**
     * 对<b>这一条</b>通道试跑（含停用/归档），返回输出、token、耗时。
     * 失败不抛：把短因和用户消息一起摆出来，管理员要看的正是「它为什么不行」。
     */
    @PostMapping("/{name}/trial")
    public Result<TrialResult> trial(@PathVariable String name, @RequestBody(required = false) TrialRequest request) {
        requireAdmin();
        if (llmChannelRegistry.find(name) == null) {
            throw new IllegalArgumentException("通道不存在: " + name);
        }
        String prompt = request == null || request.getPrompt() == null || request.getPrompt().isBlank()
                ? TRIAL_PROMPT : request.getPrompt().trim();
        String targetModel = request == null ? null : request.getTargetModel();
        long start = System.currentTimeMillis();
        try {
            LlmChatResponse r = promptOptimizeService.optimizeWith(name, prompt,
                    new PromptContext(targetModel, null, null, null, null, null));
            return Result.success(new TrialResult(true, prompt, r.content(), r.promptTokens(), r.completionTokens(),
                    System.currentTimeMillis() - start, null));
        } catch (LlmChannelException e) {
            return Result.success(new TrialResult(false, prompt, null, null, null,
                    System.currentTimeMillis() - start, e.reason() + " —— " + e.getMessage()));
        } catch (RuntimeException e) {
            return Result.success(new TrialResult(false, prompt, null, null, null,
                    System.currentTimeMillis() - start, e.getMessage()));
        }
    }

    // ───────────────────────── 校验 ─────────────────────────

    private String validName(String value) {
        String v = requiredText(value, "通道名", 64);
        if (!NAME.matcher(v).matches()) {
            throw new IllegalArgumentException("通道名只能含字母、数字、点、下划线、连字符（它会出现在 URL 路径里）");
        }
        return v;
    }

    private String requiredText(String value, String label, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + "不能为空");
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(label + "长度不能超过 " + maxLength);
        }
        return normalized;
    }

    private String validBaseUrl(String value) {
        String normalized = requiredText(value, "接口地址", 255);
        try {
            URI uri = URI.create(normalized);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null) {
                throw new IllegalArgumentException("接口地址必须是有效的 http/https URL");
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("接口地址必须是有效的 http/https URL");
        }
        return normalized;
    }

    private BigDecimal validTemperature(BigDecimal t) {
        if (t != null && (t.signum() < 0 || t.compareTo(MAX_TEMPERATURE) > 0)) {
            throw new IllegalArgumentException("temperature 必须在 0 到 2 之间，或留空表示不传");
        }
        return t;
    }

    private int validMaxTokens(int v) {
        if (v < 1 || v > MAX_TOKENS_CEILING) {
            throw new IllegalArgumentException("最大输出 token 必须在 1 到 " + MAX_TOKENS_CEILING + " 之间");
        }
        return v;
    }

    private String validTokenParam(String v) {
        if (v == null) {
            return LlmChannelSpec.TokenParam.MAX_TOKENS.stored();
        }
        String s = v.trim().toLowerCase();
        for (LlmChannelSpec.TokenParam p : LlmChannelSpec.TokenParam.values()) {
            if (p.stored().equals(s)) {
                return s;
            }
        }
        throw new IllegalArgumentException("token 字段名只能是 max_tokens / max_completion_tokens / none");
    }

    /**
     * 读超时上限是<b>前端 axios 的 120s 减 1s</b>。超过就是前端先断、后端白烧 token，
     * 用户看到的症状和超时一模一样，排查时还会以为后端已经放宽了。
     */
    private int validTimeout(int ms) {
        if (ms < LlmChannelSpec.MIN_TIMEOUT_MS || ms > LlmChannelSpec.MAX_TIMEOUT_MS) {
            throw new IllegalArgumentException("读超时必须在 " + LlmChannelSpec.MIN_TIMEOUT_MS + " 到 "
                    + LlmChannelSpec.MAX_TIMEOUT_MS + " 毫秒之间（前端 " + LlmChannelSpec.FRONTEND_TIMEOUT_MS + "ms 会先断连）");
        }
        return ms;
    }

    private int validPriority(int p) {
        if (p < 0 || p > 9_999) {
            throw new IllegalArgumentException("优先级必须在 0 到 9999 之间，小的先用");
        }
        return p;
    }

    private String validRemark(String remark) {
        if (remark != null && remark.length() > 255) {
            throw new IllegalArgumentException("备注长度不能超过 255");
        }
        return remark;
    }

    private void requireAdmin() {
        if (!UserContext.isAdmin()) {
            throw new RuntimeException("无权限访问");
        }
    }

    @Data
    public static class LlmChannelUpsertRequest {
        private String name;
        private String baseUrl;
        /** 新增必填；PATCH 时空/空白 = 保留原值 */
        private String apiKey;
        private String model;
        private BigDecimal temperature;
        /** PATCH 专用：true = 把 temperature 置空（不传给模型） */
        private Boolean clearTemperature;
        private Integer maxTokens;
        private String tokenParam;
        private Integer timeoutMs;
        private Integer priority;
        /** 新增时<b>被忽略</b>（强制 false）；PATCH 时才生效 */
        private Boolean enabled;
        private Boolean archived;
        private String remark;
    }

    @Data
    public static class TrialRequest {
        /** 留空用固定样例 */
        private String prompt;
        /** 目标生成模型（决定选哪份 prompts/*.md 模板）；留空用 default.md */
        private String targetModel;
    }

    public record TrialResult(
            boolean ok,
            String prompt,
            String content,
            Integer promptTokens,
            Integer completionTokens,
            long latencyMs,
            /** 失败时：短因 —— 用户消息 */
            String error
    ) {
    }
}
