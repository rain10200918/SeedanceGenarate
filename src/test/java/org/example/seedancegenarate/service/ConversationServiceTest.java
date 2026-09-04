package org.example.seedancegenarate.service;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationServiceTest {

    private static final long USER = 7L;
    private static final long CONV = 1L;

    private final ConversationMapper conversations = mock(ConversationMapper.class);
    private final ConversationMessageMapper messages = mock(ConversationMessageMapper.class);
    private final VideoSubmitService submitService = mock(VideoSubmitService.class);
    private final PromptOptimizeService optimizer = mock(PromptOptimizeService.class);
    private final TokenBucketRateLimitService rateLimiter = mock(TokenBucketRateLimitService.class);
    private final TransactionTemplate tx = mock(TransactionTemplate.class);
    private final ConversationMediaResolver media = mock(ConversationMediaResolver.class);
    private final List<ConversationMessage> inserted = new ArrayList<>();
    private long nextId = 100;
    private ConversationService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        // 事务模板替身：回调抛异常就把这次插入的行撤掉——模拟回滚，否则「提交失败 PENDING 卡片被回滚」这条测不出来
        when(tx.execute(any())).thenAnswer(inv -> {
            int mark = inserted.size();
            try {
                return ((TransactionCallback<Object>) inv.getArgument(0)).doInTransaction(null);
            } catch (RuntimeException e) {
                inserted.subList(mark, inserted.size()).clear();
                throw e;
            }
        });
        doAnswer(inv -> {
            int mark = inserted.size();
            try {
                ((Consumer<TransactionStatus>) inv.getArgument(0)).accept(null);
            } catch (RuntimeException e) {
                inserted.subList(mark, inserted.size()).clear();
                throw e;
            }
            return null;
        }).when(tx).executeWithoutResult(any());
        // 消息表：insert 发 id 并记账；selectList 返回已插入的（按 seq 升序）
        doAnswer(inv -> {
            ConversationMessage m = inv.getArgument(0);
            m.setId(nextId++);
            inserted.add(m);
            return 1;
        }).when(messages).insert(any(ConversationMessage.class));
        when(messages.selectList(any())).thenAnswer(inv ->
                inserted.stream().sorted(Comparator.comparingInt(ConversationMessage::getSeq)).toList());
        when(messages.updateById(any(ConversationMessage.class))).thenReturn(1);
        Conversation conv = conversation(0);
        when(conversations.lockForOwner(CONV, USER)).thenReturn(conv);
        when(conversations.selectOne(any())).thenReturn(conv);
        when(rateLimiter.tryAcquire(anyString(), any())).thenReturn(RateLimitResult.permitted());
        when(optimizer.optimize(anyString(), any())).thenReturn("主体：一只橘猫。场景：雨后的窗台。");
        VideoTask task = new VideoTask();
        task.setBizTaskId("tsk-1");
        task.setStatus("PROCESSING");
        when(submitService.submit(any())).thenReturn(task);
        // 素材解析替身：没有本地文件，历史地址原样放行（白名单和归并在 ConversationMediaResolverTest 里测）
        when(media.resolve(any(), any())).thenAnswer(inv -> inv.getArgument(0));
        service = new ConversationService(conversations, messages, submitService, optimizer, rateLimiter,
                new RateLimitConfig(), tx, new ObjectMapper(), media);
    }

    @Test
    void agentTurnProducesThreeBubblesAndSubmitsTheOptimizedPrompt() throws Exception {
        // 【测什么】Agent 模式一轮 = 用户原话 / Agent 整理的提示词 / 生成卡片，seq 连续；提交用的是整理后的提示词，幂等键指向卡片
        // 【怎么算红】提交拿原话而不是整理结果（prompt 断言）；漏了 ASSISTANT 气泡（size 断言）；幂等键不是 conv-msg:{id}
        ConversationTurnView turn = service.send(USER, CONV, request("AGENT", "一只橘猫", null));

        assertEquals(3, turn.messages().size());
        assertEquals(List.of(1, 2, 3), turn.messages().stream().map(ConversationMessageView::seq).toList());
        assertEquals("USER", turn.messages().get(0).role());
        assertEquals("一只橘猫", turn.messages().get(0).content());
        assertEquals("ASSISTANT", turn.messages().get(1).role());
        assertEquals("主体：一只橘猫。场景：雨后的窗台。", turn.messages().get(1).content());
        ConversationMessageView card = turn.messages().get(2);
        assertEquals("GENERATION", card.kind());
        assertEquals("tsk-1", card.taskId());
        assertEquals("PROCESSING", card.genStatus());

        ArgumentCaptor<VideoSubmitService.SubmitRequest> req = ArgumentCaptor.forClass(VideoSubmitService.SubmitRequest.class);
        verify(submitService).submit(req.capture());
        assertEquals("主体：一只橘猫。场景：雨后的窗台。", req.getValue().prompt());
        assertEquals("comfyui", req.getValue().provider());
        assertEquals("z-image-turbo", req.getValue().model());
        assertTrue(req.getValue().requestId().startsWith("conv-msg:"), req.getValue().requestId());
        verify(conversations).reserveSeq(CONV, 3);
        verify(conversations).setAutoTitle(CONV, "一只橘猫");
    }

    @Test
    void directTurnSkipsTheAgentAndReservesTwoSlots() throws Exception {
        // 【测什么】直出模式不调 LLM、不占 Agent 限流桶，只有两条气泡，提交原话
        // 【怎么算红】直出也去调 optimize（verify never 失败）；预留了 3 个槽位
        ConversationTurnView turn = service.send(USER, CONV, request("DIRECT", "一只橘猫", null));

        assertEquals(2, turn.messages().size());
        assertEquals("GENERATION", turn.messages().get(1).kind());
        verify(optimizer, never()).optimize(anyString(), any());
        verify(rateLimiter, never()).tryAcquire(anyString(), any());
        verify(conversations).reserveSeq(CONV, 2);
        ArgumentCaptor<VideoSubmitService.SubmitRequest> req = ArgumentCaptor.forClass(VideoSubmitService.SubmitRequest.class);
        verify(submitService).submit(req.capture());
        assertEquals("一只橘猫", req.getValue().prompt());
    }

    @Test
    void agentFailureStillGeneratesWithTheRawPromptAndSaysSo() throws Exception {
        // 【测什么】LLM 挂了这一轮不白费：ASSISTANT 气泡写明按原话生成，卡片照常提交
        // 【怎么算红】把 optimize 的异常直接抛出去（send 抛异常）；或提交没发生
        when(optimizer.optimize(anyString(), any())).thenThrow(new RuntimeException("提示词优化服务调用失败"));

        ConversationTurnView turn = service.send(USER, CONV, request("AGENT", "一只橘猫", null));

        assertEquals(3, turn.messages().size());
        assertEquals(ConversationService.AGENT_UNAVAILABLE_NOTE, turn.messages().get(1).content());
        ArgumentCaptor<VideoSubmitService.SubmitRequest> req = ArgumentCaptor.forClass(VideoSubmitService.SubmitRequest.class);
        verify(submitService).submit(req.capture());
        assertEquals("一只橘猫", req.getValue().prompt());
    }

    @Test
    void rateLimitedAgentFallsBackWithoutCallingTheLlm() throws Exception {
        // 【测什么】Agent 桶（和生成页共用 prompt:user:{userId}）拒了就按原话生成，不调 LLM
        // 【怎么算红】限流被拒仍调 optimize；或桶 key 不是 prompt:user:7
        when(rateLimiter.tryAcquire(eq("prompt:user:7"), any())).thenReturn(RateLimitResult.rejected(30));

        ConversationTurnView turn = service.send(USER, CONV, request("AGENT", "一只橘猫", null));

        assertEquals(ConversationService.AGENT_RATE_LIMITED_NOTE, turn.messages().get(1).content());
        verify(optimizer, never()).optimize(anyString(), any());
        verify(submitService).submit(any());
    }

    @Test
    void submitFailureBecomesAFailedCardAndKeepsTheAgentBubble() throws Exception {
        // 【测什么】余额不足等提交失败：生成卡片是 FAILED 并写明原因；用户和 Agent 气泡都还在
        // 【怎么算红】把 submit 异常抛给调用方（send 抛异常）；或失败卡片没有 errorMsg；或 Agent 气泡被一起回滚
        when(submitService.submit(any())).thenThrow(new RuntimeException("余额不足，请先充值"));

        ConversationTurnView turn = service.send(USER, CONV, request("AGENT", "一只橘猫", null));

        assertEquals(3, turn.messages().size());
        assertEquals("ASSISTANT", turn.messages().get(1).role());
        ConversationMessageView card = turn.messages().get(2);
        assertEquals("FAILED", card.genStatus());
        assertEquals("余额不足，请先充值", card.errorMsg());
        assertNull(card.taskId());
    }

    @Test
    void replayingTheSameClientMsgIdDoesNotStartASecondTurn() throws Exception {
        // 【测什么】网络重试重发同一 clientMsgId：不再插入、不再提交，返回已存在的那一轮
        // 【怎么算红】去掉 clientMsgId 查重：inserted 变成 3 条 + 又一次 submit
        ConversationMessage existing = new ConversationMessage();
        existing.setSeq(1);
        existing.setRole("USER");
        existing.setKind("TEXT");
        existing.setContent("一只橘猫");
        when(messages.selectOne(any())).thenReturn(existing);
        inserted.add(existing);

        service.send(USER, CONV, request("AGENT", "一只橘猫", "cm-1"));

        assertEquals(1, inserted.size(), "重放不许再插消息");
        verify(submitService, never()).submit(any());
        verify(conversations, never()).reserveSeq(any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void someoneElsesConversationIsNotFoundNotForbidden() {
        // 【测什么】归属进 WHERE：别人的对话表现为 404（D-032），而不是 403 泄露存在性
        // 【怎么算红】lockForOwner 不带 userId 查到别人的行 → 不抛
        when(conversations.lockForOwner(CONV, USER)).thenReturn(null);

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.send(USER, CONV, request("AGENT", "一只橘猫", null)));
        assertEquals(404, e.getCode());
    }

    @Test
    void applyTaskFinishedWritesMirrorStatusAndOutputShape() {
        // 【测什么】终态回填：状态镜像 + output {mediaType,url,cost}（与 canvas_node.output 同形状），按 taskId 更新
        // 【怎么算红】outputType IMAGE 没映射成 image；或 videoUrl 为空时也写 output
        service.applyTaskFinished("tsk-1", "SUCCESS", "abc.png", "IMAGE", null, new BigDecimal("0.10"));
        service.applyTaskFinished("tsk-2", "FAILED", null, null, "生成失败", null);

        ArgumentCaptor<String> output = ArgumentCaptor.forClass(String.class);
        verify(messages).applyTaskResult(eq("tsk-1"), eq("SUCCESS"), output.capture(), any());
        assertTrue(output.getValue().contains("\"mediaType\":\"image\""), output.getValue());
        assertTrue(output.getValue().contains("\"url\":\"abc.png\""), output.getValue());
        assertTrue(output.getValue().contains("\"cost\":0.10"), output.getValue());
        verify(messages).applyTaskResult(eq("tsk-2"), eq("FAILED"), (String) org.mockito.ArgumentMatchers.isNull(), eq("生成失败"));
    }

    @Test
    void emptyContentAndMissingModelAreRejectedBeforeTouchingTheDatabase() {
        // 【测什么】没内容 / 没选模型直接 400，不锁对话、不预留 seq
        // 【怎么算红】校验挪到事务里面：lockForOwner 会被调用
        assertThrows(BusinessException.class, () -> service.send(USER, CONV, request("AGENT", "   ", null)));
        assertThrows(BusinessException.class, () -> service.send(USER, CONV,
                new SendMessageRequest(null, "一只橘猫", null, "AGENT", null)));
        verify(conversations, never()).lockForOwner(any(), any());
    }

    @Test
    void autoTitleAndMediaTypeHelpers() {
        // 【测什么】标题取前 20 字并压空白；outputType 映射
        // 【怎么算红】不截断 / 不压空白 / AUDIO 映射错
        assertEquals("一二三四五六七八九十一二三四五六七八九十",
                ConversationService.autoTitle("一二三四五六七八九十一二三四五六七八九十二十一"));
        assertEquals("a b", ConversationService.autoTitle("  a \n\n b  "));
        assertEquals("audio", ConversationService.mediaType("AUDIO"));
        assertEquals("video", ConversationService.mediaType(null));
        assertFalse(ConversationService.userFacingReason(new java.io.IOException("socket")).contains("socket"));
        assertNotNull(ConversationService.userFacingReason(new RuntimeException("余额不足")));
    }

    @Test
    void listCarriesTheLatestSuccessfulResultAsCover() {
        // 【测什么】左栏缩略图：图片用结果图；视频没有封面帧用第一张参考图；音频只有类型没有图；没成功过的对话没有 cover
        // 【怎么算红】视频拿了 output.url（那是 mp4）；音频给了 url；没结果的对话被塞了 cover
        Conversation image = conversation(3);
        Conversation video = conversation(3);
        video.setId(2L);
        Conversation audio = conversation(3);
        audio.setId(3L);
        Conversation none = conversation(1);
        none.setId(4L);
        when(conversations.selectList(any())).thenReturn(List.of(image, video, audio, none));
        when(messages.latestSuccessOutputs(any())).thenReturn(List.of(
                latest(1L, "{\"mediaType\":\"image\",\"url\":\"/outputs/a.png\"}", "{}"),
                latest(2L, "{\"mediaType\":\"video\",\"url\":\"/outputs/b.mp4\"}", "{\"imageUrls\":[\"https://x/ref.jpg\"]}"),
                latest(3L, "{\"mediaType\":\"audio\",\"url\":\"/outputs/c.mp3\"}", "{}")));

        List<ConversationView> views = service.list(USER, false);

        assertEquals("/outputs/a.png", views.get(0).coverUrl());
        assertEquals("image", views.get(0).coverType());
        assertEquals("https://x/ref.jpg", views.get(1).coverUrl());
        assertEquals("video", views.get(1).coverType());
        assertNull(views.get(2).coverUrl());
        assertEquals("audio", views.get(2).coverType());
        assertNull(views.get(3).coverUrl());
        assertNull(views.get(3).coverType());
    }

    @Test
    void emptyConversationListNeverAsksForCovers() {
        // 【测什么】没有对话就不查缩略图——IN () 是 SQL 语法错，会把整个列表接口打瘫
        // 【怎么算红】去掉 covers() 里的 isEmpty 判断
        when(conversations.selectList(any())).thenReturn(List.of());

        assertTrue(service.list(USER, false).isEmpty());
        verify(messages, never()).latestSuccessOutputs(any());
    }

    private static ConversationMessageMapper.LatestOutput latest(long conversationId, String output, String genParams) {
        ConversationMessageMapper.LatestOutput row = new ConversationMessageMapper.LatestOutput();
        row.setConversationId(conversationId);
        row.setOutput(output);
        row.setGenParams(genParams);
        return row;
    }

    private static Conversation conversation(int count) {
        Conversation c = new Conversation();
        c.setId(CONV);
        c.setUserId(USER);
        c.setTitle("新对话");
        c.setTitleSource(Conversation.TITLE_AUTO);
        c.setMessageCount(count);
        c.setArchived(false);
        return c;
    }

    private static SendMessageRequest request(String mode, String content, String clientMsgId) {
        return new SendMessageRequest(clientMsgId, content, List.of(), mode,
                new SendMessageRequest.Generation("comfyui", "z-image-turbo", "16:9", 5, null));
    }
}
