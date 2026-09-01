package org.example.seedancegenarate.service;

import com.anji.captcha.model.common.ResponseModel;
import com.anji.captcha.model.vo.CaptchaVO;
import com.anji.captcha.service.CaptchaCacheService;
import com.anji.captcha.service.CaptchaService;
import com.anji.captcha.service.impl.CaptchaServiceFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.seedancegenarate.config.AjCaptchaConfig;
import org.example.seedancegenarate.config.RateLimitConfig;
import org.example.seedancegenarate.dto.CaptchaPayloads.CaptchaCheckRequest;
import org.example.seedancegenarate.dto.CaptchaPayloads.CaptchaGetRequest;
import org.example.seedancegenarate.dto.CaptchaPayloads.CaptchaScene;
import org.example.seedancegenarate.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CaptchaSecurityServiceTest {
    private CaptchaService ajCaptchaService;
    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private TokenBucketRateLimitService rateLimitService;
    private CaptchaSecurityService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ajCaptchaService = mock(CaptchaService.class);
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        rateLimitService = mock(TokenBucketRateLimitService.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(rateLimitService.tryAcquireDistributed(any(), any())).thenReturn(RateLimitResult.permitted());
        service = new CaptchaSecurityService(
                ajCaptchaService,
                redisTemplate,
                rateLimitService,
                new RateLimitConfig(),
                new ObjectMapper(),
                "test:seedance:captcha",
                120
        );
    }

    @Test
    void getReturnsOnlyTheFrontendChallengeContractWithAesDisabled() {
        // 【测什么】AJ 关闭 AES 后，get 只映射滑块所需字段，并把 secretKey 固定为空串。
        // 【怎么算红】把响应映射改成透传 AJ 的 secretKey 或漏掉任一图片字段，这条必须变红。
        CaptchaVO data = new CaptchaVO();
        data.setToken("0123456789abcdef0123456789abcdef");
        data.setOriginalImageBase64("original");
        data.setJigsawImageBase64("jigsaw");
        data.setSecretKey(null);
        when(ajCaptchaService.get(any())).thenReturn(ResponseModel.successData(data));

        var response = service.issueChallenge(
                new CaptchaGetRequest("blockPuzzle", "browser-1"),
                "203.0.113.8"
        );

        assertEquals(data.getToken(), response.token());
        assertEquals("original", response.originalImageBase64());
        assertEquals("jigsaw", response.jigsawImageBase64());
        assertEquals("", response.secretKey());
        assertEquals(120, response.expiresInSeconds());
    }

    @Test
    void getMapsAjInfrastructureFailureToServiceUnavailable() {
        // 【测什么】AJ 在 Redis/底图异常时可能返回失败 ResponseModel；get 必须响亮地报 503，而非伪装成用户验证码 400。
        // 【怎么算红】若 get 复用 check 的 invalidCaptcha mapper，本测试将拿到 400 并变红。
        when(ajCaptchaService.get(any())).thenReturn(ResponseModel.errorMsg("redis unavailable"));

        BusinessException error = assertThrows(BusinessException.class, () -> service.issueChallenge(
                new CaptchaGetRequest("blockPuzzle", "browser-1"),
                "203.0.113.8"
        ));

        assertEquals(503, error.getCode());
    }

    @Test
    void malformedPlainPointIsRejectedBeforeAjParsesIt() {
        // 【测什么】明文 pointJson 必须是带整数 x/y 的小对象，畸形输入以业务 400 收口。
        // 【怎么算红】删掉 pointJson 的 JSON/整数边界校验并让请求进入 AJ，这条必须变红。
        BusinessException error = assertThrows(BusinessException.class, () -> service.checkChallenge(
                new CaptchaCheckRequest(
                        "blockPuzzle", "browser-1", "0123456789abcdef0123456789abcdef",
                        "{\"x\":\"not-a-number\"}", CaptchaScene.LOGIN, "root"
                ),
                "203.0.113.8"
        ));

        assertEquals(400, error.getCode());
        verify(ajCaptchaService, never()).check(any());
    }

    @Test
    void pointJsonRejectsUnexpectedNestedFieldsBeforeAjParsesIt() {
        // 【测什么】pointJson 只允许 x/y 两个整数，额外嵌套字段不能进入 AJ 的异常日志路径。
        // 【怎么算红】去掉对象字段数守卫并允许 payload 字段透传，这条必须变红。
        BusinessException error = assertThrows(BusinessException.class, () -> service.checkChallenge(
                new CaptchaCheckRequest(
                        "blockPuzzle", "browser-1", "0123456789abcdef0123456789abcdef",
                        "{\"x\":42,\"y\":5,\"payload\":{\"nested\":true}}",
                        CaptchaScene.LOGIN, "root"
                ),
                "203.0.113.8"
        ));

        assertEquals(400, error.getCode());
        verify(ajCaptchaService, never()).check(any());
    }

    @Test
    void pointJsonRejectsIntegersThatOverflowTheAcceptedCoordinateRange() {
        // 【测什么】超出 int 范围的 JSON 整数不能经 intValue 截断后伪装成合法坐标。
        // 【怎么算红】去掉 canConvertToInt 守卫，4294967338 会截断成 42 并越过输入校验。
        when(valueOperations.setIfAbsent(any(), eq("1"), any(Duration.class))).thenReturn(true);
        CaptchaVO checked = new CaptchaVO();
        checked.setResult(true);
        when(ajCaptchaService.check(any())).thenReturn(ResponseModel.successData(checked));

        BusinessException error = assertThrows(BusinessException.class, () -> service.checkChallenge(
                new CaptchaCheckRequest(
                        "blockPuzzle", "browser-1", "0123456789abcdef0123456789abcdef",
                        "{\"x\":4294967338,\"y\":5}", CaptchaScene.LOGIN, "root"
                ),
                "203.0.113.8"
        ));

        assertEquals(400, error.getCode());
        verify(ajCaptchaService, never()).check(any());
    }

    @Test
    void usernameLengthGuardAppliesBeforeTrimmingPadding() {
        // 【测什么】短用户名后追加大量空格仍按原始跨边界字段长度拒绝，不能靠 trim 绕过上限。
        // 【怎么算红】若 normalizeUsername 只检查 trim 后长度，AJ 会收到请求且本测试无法捕获 400。
        when(valueOperations.setIfAbsent(any(), eq("1"), any(Duration.class))).thenReturn(true);
        CaptchaVO checked = new CaptchaVO();
        checked.setResult(true);
        when(ajCaptchaService.check(any())).thenReturn(ResponseModel.successData(checked));

        BusinessException error = assertThrows(BusinessException.class, () -> service.checkChallenge(
                new CaptchaCheckRequest(
                        "blockPuzzle", "browser-1", "0123456789abcdef0123456789abcdef",
                        "{\"x\":42,\"y\":5}", CaptchaScene.LOGIN, "root" + " ".repeat(200)
                ),
                "203.0.113.8"
        ));

        assertEquals(400, error.getCode());
        verify(ajCaptchaService, never()).check(any());
    }

    @Test
    void oneChallengeCanMintOnlyOneProof() {
        // 【测什么】同一 challenge token 先用 Redis SET NX 占位，并发或重试只能一次进入 AJ check。
        // 【怎么算红】删掉 challenge claim 或把 setIfAbsent 改成普通 set，第二次请求会放行并使本测试变红。
        when(valueOperations.setIfAbsent(any(), eq("1"), any(Duration.class)))
                .thenReturn(true, false);
        CaptchaVO checked = new CaptchaVO();
        checked.setResult(true);
        when(ajCaptchaService.check(any())).thenReturn(ResponseModel.successData(checked));

        var request = new CaptchaCheckRequest(
                "blockPuzzle", "browser-1", "0123456789abcdef0123456789abcdef",
                "{\"x\":42,\"y\":5}", CaptchaScene.LOGIN, " root "
        );
        var first = service.checkChallenge(request, "203.0.113.8");
        BusinessException second = assertThrows(
                BusinessException.class,
                () -> service.checkChallenge(request, "203.0.113.8")
        );

        assertNotNull(first.captchaProof());
        assertFalse(first.captchaProof().isBlank());
        assertEquals(400, second.getCode());
        verify(ajCaptchaService).check(any());
    }

    @Test
    void proofIsAtomicallyConsumedAndCannotBeReplayed() {
        // 【测什么】登录 proof 由单段 Redis Lua 比对绑定值并删除，第二次消费统一失败。
        // 【怎么算红】把消费改为 GET 后再 DELETE，或忽略 Lua 的未命中结果，这条必须变红。
        doReturn(1L, 0L).when(redisTemplate)
                .execute(any(RedisScript.class), anyList(), any());

        String proof = "abcdefghijklmnopqrstuvwxyzABCDE_1234567890-ab";
        var verified = service.consumeProof(
                CaptchaScene.LOGIN, " root ", proof, "203.0.113.8"
        );
        BusinessException replay = assertThrows(BusinessException.class, () -> service.consumeProof(
                CaptchaScene.LOGIN, "root", proof, "203.0.113.8"
        ));

        assertEquals(CaptchaScene.LOGIN, verified.scene());
        assertEquals("root", verified.username());
        assertEquals(400, replay.getCode());
    }

    @Test
    void distributedRateLimitFailureFailsClosedBeforeAjOrProofLookup() {
        // 【测什么】安全限流 Redis 不可用时 get/check/auth 均失败关闭，不回退进程内桶。
        // 【怎么算红】把安全入口改回 tryAcquire 本地降级或吞掉 Redis 异常，这条必须变红。
        when(rateLimitService.tryAcquireDistributed(any(), any()))
                .thenThrow(new IllegalStateException("redis unavailable"));

        BusinessException error = assertThrows(BusinessException.class, () -> service.issueChallenge(
                new CaptchaGetRequest("blockPuzzle", "browser-1"),
                "203.0.113.8"
        ));

        assertEquals(503, error.getCode());
        verify(ajCaptchaService, never()).get(any());
        verify(redisTemplate, never()).execute(any(RedisScript.class), anyList(), any());
    }

    @Test
    void usernameRateLimitUsesCaseFoldedAccountIdentity() {
        // 【测什么】ROOT/Root/root 共用一个用户名限流桶，但 proof 绑定仍保留 trim 后原始大小写。
        // 【怎么算红】用户名桶摘要若直接使用原始大小写，两次捕获到的 username key 会不同并使本测试变红。
        doReturn(1L).when(redisTemplate).execute(any(RedisScript.class), anyList(), any());
        String proof = "abcdefghijklmnopqrstuvwxyzABCDE_1234567890-ab";

        service.consumeProof(CaptchaScene.LOGIN, "ROOT", proof, "203.0.113.8");
        service.consumeProof(CaptchaScene.LOGIN, "root", proof, "203.0.113.8");

        var keyCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(rateLimitService, org.mockito.Mockito.times(4))
                .tryAcquireDistributed(keyCaptor.capture(), any());
        List<String> usernameKeys = keyCaptor.getAllValues().stream()
                .filter(key -> key.contains(":username:"))
                .toList();
        assertEquals(2, usernameKeys.size());
        assertEquals(usernameKeys.get(0), usernameKeys.get(1));
    }

    @Test
    void invalidProofCannotConsumeTheTargetAccountsUsernameBucket() {
        // 【测什么】随机/重放 proof 只能消耗来源 IP 桶，不能在未解验证码时把受害账号的用户名桶打满。
        // 【怎么算红】若 username 桶重新放到 Lua proof 核验之前，本测试会捕获到第二次限流调用并变红。
        doReturn(0L).when(redisTemplate).execute(any(RedisScript.class), anyList(), any());
        String proof = "abcdefghijklmnopqrstuvwxyzABCDE_1234567890-ab";

        BusinessException error = assertThrows(BusinessException.class, () -> service.consumeProof(
                CaptchaScene.LOGIN, "root", proof, "203.0.113.8"
        ));

        assertEquals(400, error.getCode());
        var keyCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(rateLimitService).tryAcquireDistributed(keyCaptor.capture(), any());
        assertEquals(true, keyCaptor.getValue().contains("auth:login:ip:"));
    }

    @Test
    void malformedAuthBodyStillConsumesTheIpRateLimit() {
        // 【测什么】用户名为空的畸形认证请求也先走共享 IP 桶，再以验证码 400 收口。
        // 【怎么算红】把用户名校验重新放到 IP 限流之前，rateLimitService 将零调用并使本测试变红。
        BusinessException error = assertThrows(BusinessException.class, () -> service.consumeProof(
                CaptchaScene.LOGIN, null, null, "203.0.113.8"
        ));

        assertEquals(400, error.getCode());
        var keyCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(rateLimitService).tryAcquireDistributed(keyCaptor.capture(), any());
        assertEquals(true, keyCaptor.getValue().contains("auth:login:ip:"));
        verify(redisTemplate, never()).execute(any(RedisScript.class), anyList(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void ajConfigurationRegistersSharedRedisAndReallyEmitsPlainPoints() throws Exception {
        // 【测什么】AJ 1.4.0 启动时显式注册应用 Redis SPI，且 aes=false 真实产生空 secretKey。
        // 【怎么算红】去掉 cacheService.put("redis", ...) 或把 aes.status 改回 true，这条必须变红。
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        Map<String, String> cacheValues = new HashMap<>();
        when(template.opsForValue()).thenReturn(values);
        doAnswer(invocation -> {
            cacheValues.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(values).set(anyString(), anyString(), any(Duration.class));
        when(template.hasKey(anyString()))
                .thenAnswer(invocation -> cacheValues.containsKey(invocation.getArgument(0)));
        when(values.get(anyString()))
                .thenAnswer(invocation -> cacheValues.get(invocation.getArgument(0)));
        doAnswer(invocation -> cacheValues.remove(invocation.getArgument(0)) != null)
                .when(template).delete(anyString());
        AjCaptchaConfig config = new AjCaptchaConfig();
        CaptchaCacheService cache = config.ajCaptchaRedisCache(template, "test:captcha:aj");
        CaptchaService captcha = config.ajCaptchaService(cache);
        CaptchaVO input = new CaptchaVO();
        input.setCaptchaType("blockPuzzle");
        input.setClientUid("browser-1");

        ResponseModel result = captcha.get(input);

        assertSame(cache, CaptchaServiceFactory.getCache("redis"));
        CaptchaVO data = (CaptchaVO) result.getRepData();
        assertNotNull(data);
        assertNull(data.getSecretKey());
        assertNotNull(data.getOriginalImageBase64());
        assertNotNull(data.getJigsawImageBase64());

        String hiddenPoint = cacheValues.entrySet().stream()
                .filter(entry -> entry.getKey().contains("RUNNING:CAPTCHA:"))
                .filter(entry -> !entry.getKey().contains("second-"))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElseThrow();
        JsonNode point = new ObjectMapper().readTree(hiddenPoint);
        CaptchaVO check = new CaptchaVO();
        check.setCaptchaType("blockPuzzle");
        check.setClientUid("browser-1");
        check.setToken(data.getToken());
        check.setPointJson("{\"x\":" + point.path("x").intValue()
                + ",\"y\":" + point.path("y").intValue() + "}");
        ResponseModel checked = captcha.check(check);
        assertEquals(true, checked.isSuccess());
        assertEquals(true, ((CaptchaVO) checked.getRepData()).getResult());
    }

    @Test
    void realRedisAllowsOneConsumerAndBurnsMismatchedProofs() throws Exception {
        // 【测什么】真实 Redis 上 proof 并发仅成功一次，且跨账号/场景试错后不能再给正确请求使用。
        // 【怎么算红】把 Lua 拆成 GET/DELETE、或只在 binding 相等时 DEL，这条必须变红。
        String configuredPort = System.getenv("CAPTCHA_TEST_REDIS_PORT");
        Assumptions.assumeTrue(configuredPort != null && !configuredPort.isBlank());
        LettuceConnectionFactory factory = new LettuceConnectionFactory(
                "127.0.0.1", Integer.parseInt(configuredPort)
        );
        factory.afterPropertiesSet();
        ExecutorService pool = Executors.newFixedThreadPool(32);
        try {
            StringRedisTemplate realRedis = new StringRedisTemplate(factory);
            realRedis.afterPropertiesSet();
            CaptchaService aj = mock(CaptchaService.class);
            TokenBucketRateLimitService limits = mock(TokenBucketRateLimitService.class);
            when(limits.tryAcquireDistributed(any(), any())).thenReturn(RateLimitResult.permitted());
            CaptchaVO checked = new CaptchaVO();
            checked.setResult(true);
            when(aj.check(any())).thenReturn(ResponseModel.successData(checked));
            CaptchaSecurityService realService = new CaptchaSecurityService(
                    aj, realRedis, limits, new RateLimitConfig(), new ObjectMapper(),
                    "test:captcha:" + UUID.randomUUID(), 120
            );
            String token = UUID.randomUUID().toString().replace("-", "");
            String proof = realService.checkChallenge(new CaptchaCheckRequest(
                    "blockPuzzle", "browser-1", token, "{\"x\":42,\"y\":5}",
                    CaptchaScene.LOGIN, "root"
            ), "203.0.113.8").captchaProof();
            CountDownLatch ready = new CountDownLatch(32);
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger successes = new AtomicInteger();
            for (int i = 0; i < 32; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        realService.consumeProof(CaptchaScene.LOGIN, "root", proof, "203.0.113.8");
                        successes.incrementAndGet();
                    } catch (BusinessException ignored) {
                        // 其余 31 个请求必须走统一失效分支。
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            assertEquals(true, ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            pool.shutdown();
            assertEquals(true, pool.awaitTermination(10, TimeUnit.SECONDS));
            assertEquals(1, successes.get());

            String accountProof = realService.checkChallenge(new CaptchaCheckRequest(
                    "blockPuzzle", "browser-1", UUID.randomUUID().toString().replace("-", ""),
                    "{\"x\":42,\"y\":5}", CaptchaScene.LOGIN, "root"
            ), "203.0.113.8").captchaProof();
            assertThrows(BusinessException.class, () -> realService.consumeProof(
                    CaptchaScene.LOGIN, "other", accountProof, "203.0.113.8"
            ));
            assertThrows(BusinessException.class, () -> realService.consumeProof(
                    CaptchaScene.LOGIN, "root", accountProof, "203.0.113.8"
            ));

            String sceneProof = realService.checkChallenge(new CaptchaCheckRequest(
                    "blockPuzzle", "browser-1", UUID.randomUUID().toString().replace("-", ""),
                    "{\"x\":42,\"y\":5}", CaptchaScene.LOGIN, "root"
            ), "203.0.113.8").captchaProof();
            assertThrows(BusinessException.class, () -> realService.consumeProof(
                    CaptchaScene.REGISTER, "root", sceneProof, "203.0.113.8"
            ));
            assertThrows(BusinessException.class, () -> realService.consumeProof(
                    CaptchaScene.LOGIN, "root", sceneProof, "203.0.113.8"
            ));
        } finally {
            pool.shutdownNow();
            factory.destroy();
        }
    }
}
