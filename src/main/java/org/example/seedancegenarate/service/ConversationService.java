package org.example.seedancegenarate.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.seedancegenarate.config.RateLimitConfig;
import org.example.seedancegenarate.dto.ConversationMessageView;
import org.example.seedancegenarate.dto.ConversationTurnView;
import org.example.seedancegenarate.dto.ConversationView;
import org.example.seedancegenarate.dto.SendMessageRequest;
import org.example.seedancegenarate.entity.Conversation;
import org.example.seedancegenarate.entity.ConversationMessage;
import org.example.seedancegenarate.entity.VideoTask;
import org.example.seedancegenarate.exception.BusinessException;
import org.example.seedancegenarate.mapper.ConversationMapper;
import org.example.seedancegenarate.mapper.ConversationMessageMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 对话式创作。一轮 = 用户原话 →（Agent 整理的提示词）→ 生成卡片。
 * <p>
 * 生成卡片只是 video_task 的投影：提交走 {@link VideoSubmitService#submit}，进度走既有 SSE，
 * 终态由 {@link #applyTaskFinished} 按 taskId 回填（和画布节点同一套路）。这里不碰钱、不碰供应商。
 * <p>
 * 事务切法（D-027：网络 IO 不进事务）：
 * ① 锁对话行 + 预留整轮 seq + 落用户气泡 → 提交；② Agent 调 LLM（事务外）；
 * ③ 落 Agent 气泡 → 提交；④ 落生成气泡 + submit 同一事务，失败另起事务落 FAILED 气泡。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationService {

    public static final String MODE_AGENT = "AGENT";
    public static final String MODE_DIRECT = "DIRECT";
    /** Agent 挂了这一轮不白费：说明一句，按原话生成 */
    static final String AGENT_UNAVAILABLE_NOTE = "Agent 暂时不可用，已按你的原话直接生成。";
    static final String AGENT_RATE_LIMITED_NOTE = "Agent 这一分钟用得太频繁，已按你的原话直接生成。";
    static final String SUBMIT_FAILED_FALLBACK = "提交失败，请稍后再试";
    static final int TITLE_MAX = 20;
    static final int CONTENT_MAX = 4000;
    static final int ATTACHMENT_MAX = 9;
    static final int LIST_LIMIT = 100;
    static final int PAGE_MAX = 100;
    private static final Set<String> ATTACHMENT_TYPES = Set.of("image", "video", "audio");

    private final ConversationMapper conversationMapper;
    private final ConversationMessageMapper messageMapper;
    private final VideoSubmitService videoSubmitService;
    private final PromptOptimizeService promptOptimizeService;
    private final TokenBucketRateLimitService rateLimitService;
    private final RateLimitConfig rateLimitConfig;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;

    // ───────────────────────── 对话 ─────────────────────────

    public List<ConversationView> list(Long userId, boolean includeArchived) {
        LambdaQueryWrapper<Conversation> q = new LambdaQueryWrapper<Conversation>()
                .eq(Conversation::getUserId, userId)
                .orderByDesc(Conversation::getLastMessageAt)
                .orderByDesc(Conversation::getId)
                .last("LIMIT " + LIST_LIMIT);
        if (!includeArchived) {
            q.eq(Conversation::getArchived, false);
        }
        List<Conversation> rows = conversationMapper.selectList(q);
        Map<Long, ConversationView.Cover> covers = covers(rows.stream().map(Conversation::getId).toList());
        return rows.stream().map(c -> ConversationView.of(c, covers.get(c.getId()))).toList();
    }

    public ConversationView create(Long userId, String title) {
        Conversation c = new Conversation();
        c.setUserId(userId);
        String t = trimToNull(title, 128);
        c.setTitle(t == null ? "新对话" : t);
        c.setTitleSource(t == null ? Conversation.TITLE_AUTO : Conversation.TITLE_USER);
        c.setMessageCount(0);
        c.setArchived(false);
        conversationMapper.insert(c);
        return ConversationView.of(c);
    }

    public ConversationView rename(Long userId, Long id, String title) {
        Conversation c = requireOwned(userId, id);
        String t = trimToNull(title, 128);
        if (t == null) {
            throw BusinessException.badRequest("标题不能为空");
        }
        c.setTitle(t);
        c.setTitleSource(Conversation.TITLE_USER);
        conversationMapper.updateById(c);
        return withCover(c);
    }

    public ConversationView setArchived(Long userId, Long id, boolean archived) {
        Conversation c = requireOwned(userId, id);
        c.setArchived(archived);
        conversationMapper.updateById(c);
        return withCover(c);
    }

    /** 按 seq 游标往前翻：beforeSeq 为空取最新一页；返回升序 */
    public List<ConversationMessageView> messages(Long userId, Long conversationId, Integer beforeSeq, int limit) {
        requireOwned(userId, conversationId);
        int size = Math.max(1, Math.min(limit, PAGE_MAX));
        LambdaQueryWrapper<ConversationMessage> q = new LambdaQueryWrapper<ConversationMessage>()
                .eq(ConversationMessage::getConversationId, conversationId)
                .eq(ConversationMessage::getUserId, userId)
                .orderByDesc(ConversationMessage::getSeq)
                .last("LIMIT " + size);
        if (beforeSeq != null) {
            q.lt(ConversationMessage::getSeq, beforeSeq);
        }
        List<ConversationMessage> page = new ArrayList<>(messageMapper.selectList(q));
        java.util.Collections.reverse(page);
        return page.stream().map(this::toView).toList();
    }

    // ───────────────────────── 发一轮 ─────────────────────────

    public ConversationTurnView send(Long userId, Long conversationId, SendMessageRequest req) {
        if (req == null) {
            throw BusinessException.badRequest("请求参数不能为空");
        }
        String content = trimToNull(req.content(), CONTENT_MAX);
        if (content == null) {
            throw BusinessException.badRequest("说点什么再发");
        }
        SendMessageRequest.Generation gen = req.generation();
        if (gen == null || !StringUtils.hasText(gen.model())) {
            throw BusinessException.badRequest("请选择生成模型");
        }
        boolean agent = !MODE_DIRECT.equalsIgnoreCase(req.mode());
        List<SendMessageRequest.Attachment> attachments = normalizeAttachments(req.attachments());
        String clientMsgId = trimToNull(req.clientMsgId(), 64);

        // ① 锁对话、幂等重放、预留整轮 seq、落用户气泡
        Turn turn = transactionTemplate.execute(status -> {
            Conversation conv = conversationMapper.lockForOwner(conversationId, userId);
            if (conv == null) {
                throw BusinessException.notFound("对话不存在");
            }
            if (Boolean.TRUE.equals(conv.getArchived())) {
                throw BusinessException.conflict("对话已归档，先恢复再发");
            }
            if (clientMsgId != null) {
                ConversationMessage existing = messageMapper.selectOne(new LambdaQueryWrapper<ConversationMessage>()
                        .eq(ConversationMessage::getConversationId, conversationId)
                        .eq(ConversationMessage::getClientMsgId, clientMsgId)
                        .last("LIMIT 1"));
                if (existing != null) {
                    return Turn.replay(existing.getSeq());
                }
            }
            int base = conv.getMessageCount() == null ? 0 : conv.getMessageCount();
            conversationMapper.reserveSeq(conv.getId(), agent ? 3 : 2);
            ConversationMessage user = new ConversationMessage();
            user.setConversationId(conversationId);
            user.setUserId(userId);
            user.setSeq(base + 1);
            user.setRole(ConversationMessage.ROLE_USER);
            user.setKind(ConversationMessage.KIND_TEXT);
            user.setContent(content);
            user.setAttachments(attachments.isEmpty() ? null : json(attachments));
            user.setClientMsgId(clientMsgId);
            messageMapper.insert(user);
            if (base == 0) {
                conversationMapper.setAutoTitle(conv.getId(), autoTitle(content));
            }
            return new Turn(base + 1, agent ? base + 2 : null, agent ? base + 3 : base + 2, false);
        });
        if (turn == null || turn.replay()) {
            return turnView(userId, conversationId, turn == null ? 1 : turn.userSeq());
        }

        // ② Agent：事务之外调 LLM；挂了/限流了都不让这一轮白费
        String prompt = content;
        if (agent) {
            String assistantText;
            if (!rateLimitService.tryAcquire("prompt:user:" + userId, rateLimitConfig.getPromptOptimizeUser()).allowed()) {
                assistantText = AGENT_RATE_LIMITED_NOTE;
            } else {
                try {
                    prompt = promptOptimizeService.optimize(content, new PromptContext(gen.model(),
                            count(attachments, "image"), count(attachments, "video"), count(attachments, "audio"),
                            gen.duration(), gen.ratio()));
                    assistantText = prompt;
                } catch (Exception e) {
                    log.warn("对话 Agent 整理失败，按原话生成: conversation={}, err={}", conversationId, e.getMessage());
                    prompt = content;
                    assistantText = AGENT_UNAVAILABLE_NOTE;
                }
            }
            // ③ Agent 气泡单独提交：后面 submit 失败也不能把它回滚掉，用户等的那几十秒得有东西留下
            final String text = assistantText;
            final int seq = turn.assistantSeq();
            transactionTemplate.executeWithoutResult(status ->
                    messageMapper.insert(bubble(conversationId, userId, seq, ConversationMessage.ROLE_ASSISTANT,
                            ConversationMessage.KIND_TEXT, text)));
        }

        // ④ 生成气泡 + submit 同一事务；失败另起事务落 FAILED 气泡
        List<String> imageUrls = urls(attachments, "image");
        List<String> videoUrls = urls(attachments, "video");
        List<String> audioUrls = urls(attachments, "audio");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("provider", gen.provider());
        params.put("model", gen.model());
        params.put("prompt", prompt);
        params.put("ratio", gen.ratio());
        params.put("duration", gen.duration());
        params.put("megapixels", gen.megapixels());
        params.put("imageUrls", imageUrls);
        params.put("videoUrls", videoUrls);
        params.put("audioUrls", audioUrls);
        final String genParams = json(params);
        final String finalPrompt = prompt;
        final int genSeq = turn.genSeq();
        try {
            transactionTemplate.executeWithoutResult(status -> {
                ConversationMessage g = bubble(conversationId, userId, genSeq, ConversationMessage.ROLE_ASSISTANT,
                        ConversationMessage.KIND_GENERATION, null);
                g.setGenParams(genParams);
                g.setGenStatus("PENDING");
                messageMapper.insert(g);
                VideoTask task;
                try {
                    task = videoSubmitService.submit(new VideoSubmitService.SubmitRequest(
                            userId, gen.provider(), gen.model(), finalPrompt,
                            imageUrls, videoUrls, audioUrls,
                            gen.duration(), gen.ratio(), gen.megapixels(),
                            null, "conv-msg:" + g.getId(), null));
                } catch (RuntimeException e) {
                    throw e;
                } catch (Exception e) {
                    throw new SubmitFailed(e);
                }
                g.setTaskId(task.getBizTaskId());
                g.setGenStatus(StringUtils.hasText(task.getStatus()) ? task.getStatus() : "PROCESSING");
                messageMapper.updateById(g);
            });
        } catch (RuntimeException e) {
            Throwable cause = e instanceof SubmitFailed ? e.getCause() : e;
            String reason = userFacingReason(cause);
            log.warn("对话生成提交失败，落失败卡片: conversation={}, reason={}", conversationId, reason);
            transactionTemplate.executeWithoutResult(status -> {
                ConversationMessage failed = bubble(conversationId, userId, genSeq, ConversationMessage.ROLE_ASSISTANT,
                        ConversationMessage.KIND_GENERATION, null);
                failed.setGenParams(genParams);
                failed.setGenStatus("FAILED");
                failed.setErrorMsg(truncate(reason, 512));
                messageMapper.insert(failed);
            });
        }
        return turnView(userId, conversationId, turn.userSeq());
    }

    // ───────────────────────── 终态回填 ─────────────────────────

    /** 与 SSE 同源的事件；按 taskId 回填生成卡片。重复事件幂等，找不到对应卡片就什么都不做 */
    public void applyTaskFinished(String taskId, String status, String videoUrl, String outputType,
                                  String errorMsg, BigDecimal costAmount) {
        if (!StringUtils.hasText(taskId) || !StringUtils.hasText(status)) {
            return;
        }
        String output = null;
        if (StringUtils.hasText(videoUrl)) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("mediaType", mediaType(outputType));
            out.put("url", videoUrl);
            if (costAmount != null) {
                out.put("cost", costAmount);
            }
            output = json(out);
        }
        messageMapper.applyTaskResult(taskId, status, output, truncate(errorMsg, 512));
    }

    // ───────────────────────── 内部 ─────────────────────────

    private record Turn(int userSeq, Integer assistantSeq, int genSeq, boolean replay) {
        static Turn replay(int userSeq) {
            return new Turn(userSeq, null, userSeq, true);
        }
    }

    /** 把 submit 的受检异常带出 TransactionCallback */
    private static final class SubmitFailed extends RuntimeException {
        SubmitFailed(Throwable cause) {
            super(cause);
        }
    }

    private ConversationTurnView turnView(Long userId, Long conversationId, int fromSeq) {
        Conversation conv = requireOwned(userId, conversationId);
        List<ConversationMessage> rows = messageMapper.selectList(new LambdaQueryWrapper<ConversationMessage>()
                .eq(ConversationMessage::getConversationId, conversationId)
                .eq(ConversationMessage::getUserId, userId)
                .ge(ConversationMessage::getSeq, fromSeq)
                .orderByAsc(ConversationMessage::getSeq));
        // 只取本轮：从这条用户消息到下一条用户消息之前
        List<ConversationMessageView> turn = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            ConversationMessage m = rows.get(i);
            if (i > 0 && ConversationMessage.ROLE_USER.equals(m.getRole())) {
                break;
            }
            turn.add(toView(m));
        }
        return new ConversationTurnView(withCover(conv), turn);
    }

    private ConversationView withCover(Conversation c) {
        return ConversationView.of(c, covers(List.of(c.getId())).get(c.getId()));
    }

    /** 左栏缩略图：每个对话最近一次成功结果。ids 为空不查——IN () 是语法错 */
    private Map<Long, ConversationView.Cover> covers(List<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, ConversationView.Cover> out = new HashMap<>();
        for (ConversationMessageMapper.LatestOutput row : messageMapper.latestSuccessOutputs(ids)) {
            ConversationView.Cover cover = coverOf(parse(row.getOutput()), parse(row.getGenParams()));
            if (cover != null) {
                out.put(row.getConversationId(), cover);
            }
        }
        return out;
    }

    /** 图片直接用结果图；视频没有封面帧，用第一张参考图顶上；音频没有图，只带类型让前端画图标 */
    static ConversationView.Cover coverOf(JsonNode output, JsonNode genParams) {
        if (output == null) {
            return null;
        }
        String type = output.path("mediaType").asText("video");
        String url = null;
        if ("image".equals(type)) {
            url = output.path("url").asText(null);
        } else if ("video".equals(type) && genParams != null && genParams.path("imageUrls").isArray()
                && genParams.path("imageUrls").size() > 0) {
            url = genParams.path("imageUrls").get(0).asText(null);
        }
        return new ConversationView.Cover(StringUtils.hasText(url) ? url : null, type);
    }

    private Conversation requireOwned(Long userId, Long id) {
        if (id == null) {
            throw BusinessException.badRequest("对话 ID 不能为空");
        }
        Conversation c = conversationMapper.selectOne(new LambdaQueryWrapper<Conversation>()
                .eq(Conversation::getId, id)
                .eq(Conversation::getUserId, userId));
        if (c == null) {
            throw BusinessException.notFound("对话不存在");
        }
        return c;
    }

    private static ConversationMessage bubble(Long conversationId, Long userId, int seq, String role, String kind, String content) {
        ConversationMessage m = new ConversationMessage();
        m.setConversationId(conversationId);
        m.setUserId(userId);
        m.setSeq(seq);
        m.setRole(role);
        m.setKind(kind);
        m.setContent(content);
        return m;
    }

    private List<SendMessageRequest.Attachment> normalizeAttachments(List<SendMessageRequest.Attachment> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        if (raw.size() > ATTACHMENT_MAX) {
            throw BusinessException.badRequest("参考素材最多 " + ATTACHMENT_MAX + " 个");
        }
        List<SendMessageRequest.Attachment> out = new ArrayList<>();
        for (SendMessageRequest.Attachment a : raw) {
            if (a == null || !StringUtils.hasText(a.url())) {
                continue;
            }
            String type = a.type() == null ? "image" : a.type().trim().toLowerCase();
            if (!ATTACHMENT_TYPES.contains(type)) {
                throw BusinessException.badRequest("不认识的素材类型: " + a.type());
            }
            String url = a.url().trim();
            if (!(url.startsWith("http://") || url.startsWith("https://")) || url.length() > 1024) {
                throw BusinessException.badRequest("素材地址不合法");
            }
            out.add(new SendMessageRequest.Attachment(type, url));
        }
        return out;
    }

    private static int count(List<SendMessageRequest.Attachment> list, String type) {
        return (int) list.stream().filter(a -> type.equals(a.type())).count();
    }

    private static List<String> urls(List<SendMessageRequest.Attachment> list, String type) {
        return list.stream().filter(a -> type.equals(a.type())).map(SendMessageRequest.Attachment::url).toList();
    }

    /** 提交失败给用户看的原因：仓库约定 RuntimeException 的 message 就是用户文案（GlobalExceptionHandler 同一口径） */
    static String userFacingReason(Throwable e) {
        if (e instanceof RuntimeException && StringUtils.hasText(e.getMessage())) {
            return e.getMessage();
        }
        return SUBMIT_FAILED_FALLBACK;
    }

    static String autoTitle(String content) {
        String t = content.replaceAll("\\s+", " ").trim();
        return t.length() <= TITLE_MAX ? t : t.substring(0, TITLE_MAX);
    }

    static String mediaType(String outputType) {
        if (outputType == null) {
            return "video";
        }
        return switch (outputType.toUpperCase()) {
            case "IMAGE" -> "image";
            case "AUDIO" -> "audio";
            default -> "video";
        };
    }

    private ConversationMessageView toView(ConversationMessage m) {
        return new ConversationMessageView(m.getId(), m.getSeq(), m.getRole(), m.getKind(), m.getContent(),
                parse(m.getAttachments()), m.getTaskId(), parse(m.getGenParams()), m.getGenStatus(),
                parse(m.getOutput()), m.getErrorMsg(), m.getCreateTime());
    }

    private JsonNode parse(String json) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("序列化对话数据失败", e);
        }
    }

    private static String trimToNull(String s, int max) {
        if (!StringUtils.hasText(s)) {
            return null;
        }
        String t = s.trim();
        return t.length() > max ? t.substring(0, max) : t;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() > max ? s.substring(0, max) : s;
    }
}
