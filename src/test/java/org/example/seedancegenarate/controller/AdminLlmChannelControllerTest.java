package org.example.seedancegenarate.controller;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.example.seedancegenarate.context.UserContext;
import org.example.seedancegenarate.dto.LlmChannelView;
import org.example.seedancegenarate.entity.AppUser;
import org.example.seedancegenarate.entity.LlmChannel;
import org.example.seedancegenarate.mapper.LlmChannelMapper;
import org.example.seedancegenarate.service.PromptOptimizeService;
import org.example.seedancegenarate.service.llm.LlmChannelException;
import org.example.seedancegenarate.service.llm.LlmChannelRegistry;
import org.example.seedancegenarate.service.llm.LlmChannelSpec;
import org.example.seedancegenarate.service.llm.LlmChatResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 管理端 LLM 通道的硬约束：新增强制关闭 · 密钥只出脱敏形态且空=保留 · 超时不许 ≥ 前端墙 · 归档连带停用 · 没有删除。
 */
class AdminLlmChannelControllerTest {

    private LlmChannelMapper mapper;
    private LlmChannelRegistry registry;
    private PromptOptimizeService optimizer;
    private AdminLlmChannelController controller;

    @BeforeEach
    void setUp() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
                LlmChannel.class);
        mapper = mock(LlmChannelMapper.class);
        when(mapper.update(eq(null),any(Wrapper.class))).thenReturn(1);
        registry = mock(LlmChannelRegistry.class);
        optimizer = mock(PromptOptimizeService.class);
        controller = new AdminLlmChannelController(mapper, registry, optimizer);
        AppUser admin = new AppUser();
        admin.setId(1L);
        admin.setRole("admin");
        UserContext.setUser(admin);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private static LlmChannelSpec spec(String name, boolean enabled, boolean archived) {
        return new LlmChannelSpec(name, "http://h/v1/chat/completions", "sk-a7f8e3c2-9d4b-4e5f-8a7b-6c5d4e3f2a1b",
                "gemma", 0.7, 1500, LlmChannelSpec.TokenParam.MAX_TOKENS, 100_000, 100, enabled, archived, null);
    }

    private static LlmChannel row(String name, boolean archived) {
        LlmChannel r = new LlmChannel();
        r.setName(name);
        r.setArchived(archived);
        r.setEnabled(!archived);
        return r;
    }

    private AdminLlmChannelController.LlmChannelUpsertRequest fullCreate() {
        var req = new AdminLlmChannelController.LlmChannelUpsertRequest();
        req.setName("deepseek-v3");
        req.setBaseUrl("https://api.deepseek.com/v1/chat/completions");
        req.setApiKey("sk-real-key-0123456789abcdef");
        req.setModel("deepseek-chat");
        req.setTemperature(new BigDecimal("0.7"));
        req.setTimeoutMs(60_000);
        req.setPriority(200);
        return req;
    }

    // 【测什么】显式确认图片能力落SET，换模型未确认安全关闭，同模型普通编辑不清能力。
    // 【怎么算红】删掉能力SET或模型变化检测，捕获SQL与参数断言失败。
    @Test void imageCapabilityIsExplicitAndModelChangesResetIt() {
        var existing=row("default",false); existing.setModel("old");existing.setBaseUrl("http://h/v1");existing.setSupportsImages(true);
        when(mapper.selectById("default")).thenReturn(existing);
        var req=new AdminLlmChannelController.LlmChannelUpsertRequest(); req.setModel("new");
        controller.update("default",req);
        ArgumentCaptor<Wrapper<LlmChannel>> capture=ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).update(eq(null),capture.capture());
        var wrapper=(com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<LlmChannel>)capture.getValue();
        assertTrue(wrapper.getSqlSet().contains("supports_images"));
        assertTrue(wrapper.getParamNameValuePairs().containsValue(false));
        assertTrue(wrapper.getSqlSegment().contains("model") && wrapper.getSqlSegment().contains("base_url"));
        org.mockito.Mockito.clearInvocations(mapper);
        req.setSupportsImages(true); controller.update("default",req);
        verify(mapper).update(eq(null),capture.capture());
        wrapper=(com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<LlmChannel>)capture.getValue();
        assertTrue(wrapper.getParamNameValuePairs().containsValue(true));
        org.mockito.Mockito.clearInvocations(mapper);
        var plain=new AdminLlmChannelController.LlmChannelUpsertRequest();plain.setRemark("note");
        controller.update("default",plain);verify(mapper).update(eq(null),capture.capture());
        assertFalse(capture.getValue().getSqlSet().contains("supports_images"));
    }

    // 【测什么】旧编辑确认不能覆盖新模型，条件更新竞争失败返回409；无变化驱动返回0可安全重放。
    // 【怎么算红】删expected比较或忽略更新行数，则冲突不再抛出；把0一律失败则重放失败。
    @Test void imageConfirmationFencesConcurrentModelChangesAndAcceptsIdenticalReplay() {
        var existing=row("default",false);existing.setModel("m1");existing.setBaseUrl("http://h/v1");existing.setSupportsImages(true);
        when(mapper.selectById("default")).thenReturn(existing);
        var req=new AdminLlmChannelController.LlmChannelUpsertRequest(); req.setSupportsImages(true);
        req.setExpectedModel("m0");req.setExpectedBaseUrl("http://h/v1");
        assertEquals(409,assertThrows(org.example.seedancegenarate.exception.BusinessException.class,()->controller.update("default",req)).getCode());
        req.setExpectedModel("m1");
        when(mapper.update(eq(null),any(Wrapper.class))).thenReturn(0);
        controller.update("default",req);
        var competing=row("default",false);competing.setModel("m2");competing.setBaseUrl("http://h/v1");competing.setSupportsImages(true);
        when(mapper.selectById("default")).thenReturn(existing,competing);
        assertEquals(409,assertThrows(org.example.seedancegenarate.exception.BusinessException.class,()->controller.update("default",req)).getCode());
        req.setExpectedBaseUrl(null);
        assertThrows(IllegalArgumentException.class,()->controller.update("default",req));
        assertEquals(409,controller.businessFailure(org.example.seedancegenarate.exception.BusinessException.conflict("changed")).getStatusCode().value());
    }

    @Test
    void listNeverExposesThePlainKey() {
        // 【测什么】列表返回的是脱敏形态，且返回类型上根本没有明文字段
        // 【怎么算红】把 spec 直接返回 / 或 view 里加一个 apiKey 字段 —— 任何有管理员会话的人都能拿到全部第三方密钥
        when(registry.channelsStrict()).thenReturn(List.of(spec("default", true, false)));
        List<LlmChannelView> views = controller.list(false).getData();
        assertEquals(1, views.size());
        assertEquals("sk-a7f••••••1b", views.get(0).apiKeyMasked());
        assertTrue(Arrays.stream(LlmChannelView.class.getRecordComponents())
                        .noneMatch(c -> c.getName().equals("apiKey")),
                "LlmChannelView 上不许有明文 apiKey 字段");
    }

    // 【测什么】另一实例保存能力后，管理列表绕过本实例30秒旧缓存，数据库失败也不返回假新状态。
    // 【怎么算红】管理list调用channels缓存版时，显式false仍显示true；数据库故障时错误地返回旧列表。
    @Test void adminListDoesNotReuseAnotherNodesStaleCapability() {
        var value=row("vision",false);value.setModel("m");value.setBaseUrl("http://h/v1");value.setSupportsImages(true);
        when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of(value));
        var live=new LlmChannelRegistry(mapper,new org.example.seedancegenarate.config.PromptOptimizeConfig());
        var admin=new AdminLlmChannelController(mapper,live,optimizer);
        assertTrue(live.channels().get(0).supportsImages());
        value.setSupportsImages(false); // another node's committed write; this registry was not invalidated
        assertFalse(admin.list(false).getData().get(0).supportsImages());
        when(mapper.selectList(any(Wrapper.class))).thenThrow(new RuntimeException("database offline"));
        assertThrows(RuntimeException.class,()->admin.list(false));
    }

    // 【测什么】MVC陈旧确认真实HTTP409，畸形字段400而非200包装。
    // 【怎么算红】删除局部异常处理，MockMvc状态断言失败或异常逃逸。
    @Test void staleImageConfirmationUsesHttpConflict() throws Exception {
        var existing=row("default",false);existing.setModel("new");existing.setBaseUrl("http://h/v1");
        when(mapper.selectById("default")).thenReturn(existing);
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/admin/llm-channels/default")
                .contentType("application/json").content("{\"supportsImages\":true,\"expectedModel\":\"old\",\"expectedBaseUrl\":\"http://h/v1\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value(409));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/admin/llm-channels/default")
                .contentType("application/json").content("{\"supportsImages\":{}}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
    }

    @Test
    void listHidesArchivedUnlessAsked() {
        // 【测什么】默认不列归档；includeArchived=true 才列
        // 【怎么算红】默认全列 —— 归档等于没归档，列表越用越长
        when(registry.channelsStrict()).thenReturn(List.of(spec("default", true, false), spec("old", false, true)));
        assertEquals(1, controller.list(false).getData().size());
        assertEquals(2, controller.list(true).getData().size());
    }

    @Test
    void newChannelsAreAlwaysCreatedDisabledEvenIfTheRequestSaysOtherwise() {
        // 【测什么】POST 时 enabled 强制 false，请求体写 true 也不认；写完 invalidate
        // 【怎么算红】尊重请求体 —— 一条没试跑过的第三方通道立刻开始服务真实用户，输出质量没人看过
        var req = fullCreate();
        req.setEnabled(true);
        when(mapper.selectById("deepseek-v3")).thenReturn(null);
        controller.create(req);
        ArgumentCaptor<LlmChannel> captor = ArgumentCaptor.forClass(LlmChannel.class);
        verify(mapper).insert(captor.capture());
        assertFalse(captor.getValue().getEnabled(), "新增必须是关闭的");
        assertFalse(captor.getValue().getArchived());
        // 【测什么】新通道不能继承旧配置的读图权限。
        // 【怎么算红】新增默认未写false时此字段缺失或为null。
        assertEquals(Boolean.FALSE,captor.getValue().getSupportsImages());
        assertEquals("sk-real-key-0123456789abcdef", captor.getValue().getApiKey());
        assertEquals("max_tokens", captor.getValue().getTokenParam(), "token_param 缺省按 max_tokens");
        verify(registry).invalidate();
    }

    @Test
    void timeoutAtOrAboveTheFrontendWallIsRejected() {
        // 【测什么】timeoutMs ≥ 前端 120000 直接拒；119000 可以
        // 【怎么算红】不校验 —— 管理员填 150000，前端 120s 先断，后端还在白烧 token，症状和超时一模一样
        var req = fullCreate();
        req.setTimeoutMs(LlmChannelSpec.FRONTEND_TIMEOUT_MS);
        when(mapper.selectById(anyString())).thenReturn(null);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> controller.create(req));
        assertTrue(e.getMessage().contains("前端"), "错误要说清是前端会先断，实际=" + e.getMessage());
        verify(mapper, never()).insert(any(LlmChannel.class));

        req.setTimeoutMs(LlmChannelSpec.MAX_TIMEOUT_MS);
        controller.create(req);
        verify(mapper).insert(any(LlmChannel.class));
    }

    @Test
    void nameMustBeUrlSafeAndUnique() {
        // 【测什么】通道名带斜杠/空格拒绝；已存在拒绝
        // 【怎么算红】不校验 —— 名字进 PATCH 路径变成两段，或者 insert 撞主键抛一个数据库错给前端
        var req = fullCreate();
        req.setName("bad name/with slash");
        assertThrows(IllegalArgumentException.class, () -> controller.create(req));
        req.setName("deepseek-v3");
        when(mapper.selectById("deepseek-v3")).thenReturn(row("deepseek-v3", false));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> controller.create(req)).getMessage().contains("已存在"));
    }

    @Test
    void patchWithBlankOrMaskedKeyKeepsTheStoredKey() {
        // 【测什么】PATCH 的 apiKey 为空/空白 = 不动；提交回脱敏显示值（含 •）直接拒绝
        // 【怎么算红】(a) 空串也 set —— 改个备注把密钥清空，通道下一次调用 401；
        //            (b) 不拦脱敏值 —— 前端把 "sk-a7f••••••1b" 原样提交，真 key 被覆盖成一串点
        when(mapper.selectById("default")).thenReturn(row("default", false));
        var req = new AdminLlmChannelController.LlmChannelUpsertRequest();
        req.setApiKey("   ");
        req.setRemark("只改备注");
        controller.update("default", req);
        ArgumentCaptor<Wrapper<LlmChannel>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).update(eq(null), captor.capture());
        String sql = captor.getValue().getSqlSet();
        assertTrue(sql.contains("remark"), "备注要改，实际=" + sql);
        assertFalse(sql.contains("api_key"), "空白密钥不许进 SET，实际=" + sql);

        var masked = new AdminLlmChannelController.LlmChannelUpsertRequest();
        masked.setApiKey("sk-a7f••••••1b");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> controller.update("default", masked))
                .getMessage().contains("脱敏"));
    }

    @Test
    void archivingAlsoDisablesAndArchivedCannotBeEnabled() {
        // 【测什么】archived=true 连带 enabled=false；归档通道直接 enabled=true 被拒
        // 【怎么算红】分开 —— 一条「已归档但还在路由里」的通道列表里看不见却仍在服务，最难发现
        when(mapper.selectById("old")).thenReturn(row("old", false));
        var archive = new AdminLlmChannelController.LlmChannelUpsertRequest();
        archive.setArchived(true);
        controller.update("old", archive);
        ArgumentCaptor<Wrapper<LlmChannel>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).update(eq(null), captor.capture());
        String sql = captor.getValue().getSqlSet();
        assertTrue(sql.contains("archived") && sql.contains("enabled"), "归档必须连带停用，实际=" + sql);

        when(mapper.selectById("old")).thenReturn(row("old", true));
        var enable = new AdminLlmChannelController.LlmChannelUpsertRequest();
        enable.setEnabled(true);
        assertTrue(assertThrows(IllegalArgumentException.class, () -> controller.update("old", enable))
                .getMessage().contains("归档"));
    }

    @Test
    void trialTargetsTheNamedChannelAndReportsFailuresInsteadOfThrowing() throws Exception {
        // 【测什么】试跑走 optimizeWith(name…)，用 TRIAL 场景；通道失败时返回 ok=false + 短因，不抛
        // 【怎么算红】(a) 试跑走普通 optimize() —— 测的是路由选中的通道不是指定的那条；
        //            (b) 失败直接抛 —— 管理员只看到 500，看不到「HTTP 401 配置错误」这个真正要修的东西
        when(registry.find("candidate")).thenReturn(spec("candidate", false, false));
        when(optimizer.optimizeWith(eq("candidate"), anyString(), any()))
                .thenReturn(new LlmChatResponse("A ginger cat dozing...", 30, 40));
        var ok = controller.trial("candidate", null).getData();
        assertTrue(ok.ok());
        assertEquals("A ginger cat dozing...", ok.content());
        assertEquals(AdminLlmChannelController.TRIAL_PROMPT, ok.prompt());
        verify(optimizer, never()).optimize(anyString(), any());

        when(optimizer.optimizeWith(eq("candidate"), anyString(), any()))
                .thenThrow(LlmChannelException.failoverable("HTTP 401 认证失败（密钥或权限，属配置错误）", null));
        var bad = controller.trial("candidate", null).getData();
        assertFalse(bad.ok());
        assertTrue(bad.error().contains("401"));
        assertNull(bad.content());

        when(registry.find("typo")).thenReturn(null);
        assertThrows(IllegalArgumentException.class, () -> controller.trial("typo", null));
    }

    @Test
    void thereIsNoDeleteEndpointAtAll() {
        // 【测什么】控制器上没有任何 DELETE 映射
        // 【怎么算红】谁加了 @DeleteMapping —— prompt_token_usage.llm_channel 悬空，历史调用查无此通道
        for (Method m : AdminLlmChannelController.class.getDeclaredMethods()) {
            assertFalse(m.isAnnotationPresent(DeleteMapping.class), "不许有 DELETE: " + m.getName());
            RequestMapping rm = m.getAnnotation(RequestMapping.class);
            if (rm != null) {
                assertFalse(Arrays.asList(rm.method()).contains(RequestMethod.DELETE), "不许有 DELETE: " + m.getName());
            }
        }
    }

    @Test
    void nonAdminIsRefusedBeforeAnySideEffect() {
        // 【测什么】非管理员调 POST/PATCH/试跑一律拒绝，且不碰库
        // 【怎么算红】漏掉 requireAdmin —— 普通用户能加一条指向自己服务器的通道，把所有用户的提示词都发过去
        AppUser user = new AppUser();
        user.setId(2L);
        user.setRole("user");
        UserContext.setUser(user);
        assertThrows(RuntimeException.class, () -> controller.create(fullCreate()));
        assertThrows(RuntimeException.class, () -> controller.update("default", new AdminLlmChannelController.LlmChannelUpsertRequest()));
        assertThrows(RuntimeException.class, () -> controller.trial("default", null));
        assertThrows(RuntimeException.class, () -> controller.list(true));
        verify(mapper, never()).insert(any(LlmChannel.class));
        verify(mapper, never()).update(any(), any());
    }
}
