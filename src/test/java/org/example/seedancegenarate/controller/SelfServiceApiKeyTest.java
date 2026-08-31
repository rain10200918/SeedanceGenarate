package org.example.seedancegenarate.controller;

import org.example.seedancegenarate.config.RateLimitConfig;
import org.example.seedancegenarate.context.UserContext;
import org.example.seedancegenarate.dto.CreateApiKeyResponse;
import org.example.seedancegenarate.dto.SelfApiKeyRequest;
import org.example.seedancegenarate.entity.ApiKey;
import org.example.seedancegenarate.entity.Result;
import org.example.seedancegenarate.exception.BusinessException;
import org.example.seedancegenarate.service.ApiKeyService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * API Key 自助创建的越权与放大守卫。
 * <p>
 * 这个接口<b>签发凭证</b>，而凭证花的是账号余额（企业账号是管理员一次性充值的大额）。
 * 所以它的失败模式不是「功能不好用」，是「任何人都能拿别人的钱生成视频」。
 */
class SelfServiceApiKeyTest {

    private static final Long ME = 7L;
    private static final Long SOMEONE_ELSE = 99L;

    private ApiKeyService apiKeyService;
    private org.example.seedancegenarate.mapper.AppUserMapper appUserMapper;
    private org.example.seedancegenarate.service.ConcurrencyPolicy concurrencyPolicy;
    private ApiKeyController controller;

    @BeforeEach
    void setUp() {
        apiKeyService = mock(ApiKeyService.class);
        appUserMapper = mock(org.example.seedancegenarate.mapper.AppUserMapper.class);
        org.example.seedancegenarate.config.ConcurrencyProperties concProps =
                new org.example.seedancegenarate.config.ConcurrencyProperties();
        concurrencyPolicy = new org.example.seedancegenarate.service.ConcurrencyPolicy(concProps, new RateLimitConfig());
        org.springframework.test.util.ReflectionTestUtils.setField(concurrencyPolicy, "taskTimeoutMinutes", 60);
        org.springframework.test.util.ReflectionTestUtils.setField(concurrencyPolicy, "timeoutRetryMax", 2);
        controller = new ApiKeyController(apiKeyService, appUserMapper, concurrencyPolicy);
        ReflectionTestUtils.setField(controller, "maxPerUser", 50);
        setCurrentUser(ME);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private void setCurrentUser(Long userId) {
        org.example.seedancegenarate.entity.AppUser user = new org.example.seedancegenarate.entity.AppUser();
        user.setId(userId);
        user.setRole("USER");
        UserContext.setUser(user);
    }

    private ApiKey record(Long id, Long userId) {
        ApiKey key = new ApiKey();
        key.setId(id);
        key.setUserId(userId);
        key.setKeyPrefix("sk-abcd1234");
        key.setStatus("ENABLED");
        return key;
    }

    private void stubCreate() {
        when(apiKeyService.createOwned(anyLong(), any(), any(), any(), any()))
                .thenAnswer(inv -> new ApiKeyService.CreatedApiKey(
                        "sk-plaintext", record(1L, inv.getArgument(0))));
    }

    @Test
    void ownerAlwaysComesFromTheSessionNeverFromTheRequestBody() throws Exception {
        // 【测什么】属主取自 UserContext，且请求 DTO 里**根本没有** userId 字段
        // 【怎么算红】给 SelfApiKeyRequest 加上 userId 并传进 service ——
        //          任何登录用户都能给别人账号签发 key，而那把 key 花的是**别人的余额**。
        //          管理端 create 正是从请求体读 userId 的，复用它就会踩这个坑
        for (Field f : SelfApiKeyRequest.class.getDeclaredFields()) {
            assertFalse(f.getName().toLowerCase().contains("userid"),
                    "自助 DTO 不能有 userId 字段，实际有: " + f.getName());
        }

        stubCreate();
        controller.create(new SelfApiKeyRequest(), new MockHttpServletRequest());

        verify(apiKeyService).createOwned(eq(ME), any(), any(), eq(ME), any());
    }

    @Test
    void renamingSomeoneElsesKeyIsReportedAsNotFound() {
        // 【测什么】改别人的 key → 「不存在」
        // 【怎么算红】(a) 不带归属条件就更新 —— 直接改掉别人的 key；
        //          (b) 返回 403 —— 等于告诉攻击者「这个 id 存在」，送一个可枚举的存在性预言机
        when(apiKeyService.renameOwned(eq(42L), eq(ME), anyString())).thenReturn(false);

        SelfApiKeyRequest req = new SelfApiKeyRequest();
        req.setName("别人的钥匙");
        BusinessException thrown = assertThrows(BusinessException.class,
                () -> controller.rename(42L, req));

        assertEquals(404, thrown.getCode(), "必须是 404 而不是 403，实际=" + thrown.getCode());
    }

    @Test
    void revokingSomeoneElsesKeyIsReportedAsNotFound() {
        // 【测什么】撤销别人的 key → 「不存在」，且**没有真的撤销**
        // 【怎么算红】调用不带归属的 revoke(id) —— 任何人能把别人的生产 key 停掉，
        //          对方整条 API 接入立刻中断
        when(apiKeyService.revokeOwned(eq(42L), eq(ME))).thenReturn(false);

        BusinessException thrown = assertThrows(BusinessException.class, () -> controller.revoke(42L));

        assertEquals(404, thrown.getCode());
        verify(apiKeyService, never()).revoke(anyLong());
    }

    @Test
    void listOnlyAsksForMyOwnKeys() {
        // 【测什么】列表只按当前用户查
        // 【怎么算红】调 listAll() —— 所有人的 key 前缀、备注、回调地址全泄漏给任意登录用户
        when(apiKeyService.listByOwner(ME)).thenReturn(List.of(record(1L, ME)));

        controller.list();

        verify(apiKeyService).listByOwner(ME);
        verify(apiKeyService, never()).listAll();
    }

    @Test
    void reachingTheKeyLimitIsRefusedBeforeAnyKeyIsIssued() {
        // 【测什么】达到 50 把上限时拒绝，且**不签发**
        // 【怎么算红】不检查上限 —— 一个脚本能建十万把，每把都是独立的泄漏面，
        //          而且撤销时得一把把撤
        when(apiKeyService.countByOwner(ME)).thenReturn(50L);

        BusinessException thrown = assertThrows(BusinessException.class,
                () -> controller.create(new SelfApiKeyRequest(), new MockHttpServletRequest()));

        assertTrue(thrown.getMessage().contains("50"), "文案要带上限值，实际=" + thrown.getMessage());
        verify(apiKeyService, never()).createOwned(anyLong(), any(), any(), any(), any());
    }

    @Test
    void oneBelowTheLimitStillWorks() {
        // 【测什么】边界：第 50 把（已有 49）必须能建
        // 【怎么算红】判定写成 >= existing+1 之类的差一错误 —— 用户永远建不满自己买到的额度
        when(apiKeyService.countByOwner(ME)).thenReturn(49L);
        stubCreate();

        controller.create(new SelfApiKeyRequest(), new MockHttpServletRequest());

        verify(apiKeyService).createOwned(eq(ME), any(), any(), eq(ME), any());
    }

    @Test
    void blankRemarkGetsADefaultNameInsteadOfNull() {
        // 【测什么】备注不强制；不填时给一个能认出来的默认名
        // 【怎么算红】直接落 null —— 用户列表里一排「未命名」，撤销时根本分不清哪把是哪把，
        //          而这正是「哪把泄漏了就撤哪把」最需要的信息
        when(apiKeyService.countByOwner(ME)).thenReturn(2L);
        stubCreate();

        controller.create(new SelfApiKeyRequest(), new MockHttpServletRequest());

        verify(apiKeyService).createOwned(eq(ME), eq("API Key 3"), isNull(), eq(ME), any());
    }

    @Test
    void issuerAndSourceIpAreRecordedForAudit() {
        // 【测什么】签发者与来源 IP 落库
        // 【怎么算红】不记 —— 明文只展示一次，一旦泄漏，除了这两列没有任何回溯依据
        when(apiKeyService.countByOwner(ME)).thenReturn(0L);
        stubCreate();
        MockHttpServletRequest servletRequest = new MockHttpServletRequest();
        servletRequest.setRemoteAddr("203.0.113.9");

        controller.create(new SelfApiKeyRequest(), servletRequest);

        verify(apiKeyService).createOwned(eq(ME), any(), any(), eq(ME), eq("203.0.113.9"));
    }

    @Test
    void plainKeyIsReturnedExactlyOnceOnCreation() {
        // 【测什么】明文只在创建响应里；列表视图里没有它
        // 【怎么算红】把明文塞进 ApiKeyView —— 列表接口会反复吐出可用凭证，
        //          浏览器缓存、日志、截图里到处都是
        when(apiKeyService.countByOwner(ME)).thenReturn(0L);
        stubCreate();

        Result<CreateApiKeyResponse> created =
                controller.create(new SelfApiKeyRequest(), new MockHttpServletRequest());

        assertEquals("sk-plaintext", created.getData().plainKey());
        List<String> viewFields = java.util.Arrays.stream(
                        org.example.seedancegenarate.dto.ApiKeyView.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName).toList();
        assertFalse(viewFields.contains("plainKey"), "列表视图不能含明文，实际字段=" + viewFields);
        assertFalse(viewFields.contains("keyHash"), "视图不能含哈希，实际字段=" + viewFields);
        assertFalse(viewFields.contains("webhookSecret"), "视图不能含回调密钥，实际字段=" + viewFields);
    }

    private org.example.seedancegenarate.entity.AppUser owner(Integer accountMax) {
        org.example.seedancegenarate.entity.AppUser u = new org.example.seedancegenarate.entity.AppUser();
        u.setId(ME);
        u.setConcurrencyOverride(accountMax);
        return u;
    }

    private org.example.seedancegenarate.dto.ApiKeyShareRequest share(Integer n) {
        org.example.seedancegenarate.dto.ApiKeyShareRequest r =
                new org.example.seedancegenarate.dto.ApiKeyShareRequest();
        r.setMaxConcurrency(n);
        return r;
    }

    @Test
    void shareAboveTheAccountTotalIsRejectedNotSilentlyClamped() {
        // 【测什么】给单把 key 分配的份额超过账号总量时，直接报错并说清总量是多少
        // 【怎么算红】静默按总量保存 —— 用户填 999、页面显示 999、实际按 50 跑，
        //          他会一直以为这把 key 分到了 999，排查「为什么只跑得动 50」时
        //          页面上白纸黑字写着 999，没人会怀疑是当初被悄悄改掉了
        setCurrentUser(ME);
        when(appUserMapper.selectById(ME)).thenReturn(owner(50));

        BusinessException e = assertThrows(BusinessException.class,
                () -> controller.setShare(1L, share(999)));
        assertTrue(e.getMessage().contains("50"), "要说清总量是多少，实际=" + e.getMessage());
        verify(apiKeyService, never()).setShareOwned(any(), any(), any());
    }

    @Test
    void shareWithinTheAccountTotalIsSaved() {
        // 【测什么】正常分配（份额 <= 总量）落库
        // 【怎么算红】把边界判成 >= —— 企业想把整份总量都给生产那把 key（很常见）却存不进去
        setCurrentUser(ME);
        when(appUserMapper.selectById(ME)).thenReturn(owner(50));
        when(apiKeyService.setShareOwned(1L, ME, 50)).thenReturn(true);

        controller.setShare(1L, share(50));

        verify(apiKeyService).setShareOwned(1L, ME, 50);
    }

    @Test
    void clearingTheShareIsAllowedAndWritesNull() {
        // 【测什么】留空 = 取消分配，要真的写 null 进去
        // 【怎么算红】把 null 当「不改」跳过 —— 用户点了「取消分配」但份额还在，
        //          这把 key 继续被一个他以为已经删掉的数字卡着
        setCurrentUser(ME);
        when(appUserMapper.selectById(ME)).thenReturn(owner(50));
        when(apiKeyService.setShareOwned(1L, ME, null)).thenReturn(true);

        controller.setShare(1L, share(null));

        verify(apiKeyService).setShareOwned(1L, ME, null);
    }

    @Test
    void accountWithNoTotalCannotAllocateAShareAtAll() {
        // 【测什么】账号本身没有「同时可跑任务数」总量时，分配份额直接被拒（没有蛋糕就没法切）
        // 【怎么算红】放行 —— 两个后果：①用户能给 50 把 key 各设一个份额，凭空往对账每 2 秒
        //          扫描的集合里塞 50 个 Redis 桶，自助给后台加负载；②这个数字对他毫无意义，
        //          页面上却显示得像生效了。另一种写法是拿 null 总量去比大小 —— 直接 NPE
        setCurrentUser(ME);
        when(appUserMapper.selectById(ME)).thenReturn(owner(null));

        BusinessException e = assertThrows(BusinessException.class,
                () -> controller.setShare(1L, share(3)));

        assertTrue(e.getMessage().contains("没有设置"), "报错要说清为什么，实际=" + e.getMessage());
        verify(apiKeyService, never()).setShareOwned(any(), any(), any());
    }

    @Test
    void accountWithNoTotalCanStillClearAShare() {
        // 【测什么】上一条只挡「设值」，清空（null）必须仍然放行
        // 【怎么算红】把 null 也一起拒了 —— 一个账号先有席位、设了份额，后来席位被管理员撤掉，
        //          那把 key 上的份额就永远删不掉了，而它还在被 Lua 当成一个真实的桶
        setCurrentUser(ME);
        when(appUserMapper.selectById(ME)).thenReturn(owner(null));
        when(apiKeyService.setShareOwned(1L, ME, null)).thenReturn(true);

        controller.setShare(1L, share(null));

        verify(apiKeyService).setShareOwned(1L, ME, null);
    }

    @Test
    void settingShareOnSomeoneElsesKeyReportsNotFound() {
        // 【测什么】改别人的 key 报「不存在」而不是「无权限」
        // 【怎么算红】返回 403 —— 等于送一个可枚举的存在性预言机，
        //          攻击者能靠 403/404 的差异把全平台的 key id 摸出来
        setCurrentUser(ME);
        when(appUserMapper.selectById(ME)).thenReturn(owner(50));
        when(apiKeyService.setShareOwned(any(), any(), any())).thenReturn(false);

        BusinessException e = assertThrows(BusinessException.class,
                () -> controller.setShare(1L, share(5)));
        assertEquals(404, e.getCode(), "必须是 404 而不是 403，实际=" + e.getCode());
    }

    @Test
    void negativeShareIsRejected() {
        // 【测什么】负数直接拒，并告诉用户「不限制留空、0 是停用」
        // 【怎么算红】不校验 —— 负数会让「已用 >= 上限」恒成立，这把 key 一个任务都提交不了，
        //          而错误现场在提交路径上、离这个输入框很远
        setCurrentUser(ME);
        when(appUserMapper.selectById(ME)).thenReturn(owner(50));

        assertThrows(BusinessException.class, () -> controller.setShare(1L, share(-1)));
        verify(apiKeyService, never()).setShareOwned(any(), any(), any());
    }
}
