package org.example.seedancegenarate.service;

import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.example.seedancegenarate.config.RateLimitConfig;
import org.example.seedancegenarate.dto.CaptchaPayloads.CaptchaScene;
import org.example.seedancegenarate.dto.RegistrationPayloads.EmailCodeResponse;
import org.example.seedancegenarate.dto.RegistrationPayloads.RegisterRequest;
import org.example.seedancegenarate.exception.BusinessException;
import org.example.seedancegenarate.mapper.AppUserMapper;
import org.example.seedancegenarate.mapper.InviteCodeMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.mail.javamail.JavaMailSender;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/** 可选真实 Redis 探针；仅设置 REGISTRATION_EMAIL_TEST_REDIS_PORT 时运行。 */
class RegistrationEmailSessionRedisIntegrationTest {
    private static final Pattern MAIL_CODE = Pattern.compile("([0-9]{6})");

    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redis;
    private RegistrationEmailSessionService service;
    private JavaMailSender mailSender;
    private CopyOnWriteArrayList<String> deliveredCodes;
    private String keyPrefix;

    @BeforeEach
    void setUp() {
        String configuredPort = System.getenv("REGISTRATION_EMAIL_TEST_REDIS_PORT");
        Assumptions.assumeTrue(configuredPort != null && !configuredPort.isBlank());
        RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration(
                "127.0.0.1",
                Integer.parseInt(configuredPort)
        );
        String password = System.getenv("REGISTRATION_EMAIL_TEST_REDIS_PASSWORD");
        if (password != null && !password.isBlank()) {
            configuration.setPassword(RedisPassword.of(password));
        }
        connectionFactory = new LettuceConnectionFactory(configuration);
        connectionFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(connectionFactory);
        redis.afterPropertiesSet();
        keyPrefix = "test:registration-email:" + UUID.randomUUID();
        deliveredCodes = new CopyOnWriteArrayList<>();
        mailSender = mock(JavaMailSender.class);
        captureCodes(mailSender);
        service = newService(mailSender);
    }

    @AfterEach
    void tearDown() {
        if (redis != null && keyPrefix != null) {
            Set<String> keys = redis.keys(keyPrefix + ":*");
            if (keys != null && !keys.isEmpty()) {
                redis.delete(keys);
            }
        }
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @Test
    void thirtyTwoConcurrentConsumersProduceExactlyOneVerifiedRegistration() throws Exception {
        // 【测什么】真实 Redis 的最终 Lua 在 32 并发下只允许一个请求消费 ticket。
        // 【怎么算红】若消费被拆成 GET/DELETE 或不删除 ticket，successes 会大于 1。
        SentSession sent = sendSession("alice", "alice@example.com");
        RegisterRequest request = registerRequest(sent, "alice", sent.code());
        ExecutorService pool = Executors.newFixedThreadPool(32);
        CountDownLatch ready = new CountDownLatch(32);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        ConcurrentLinkedQueue<Throwable> unexpected = new ConcurrentLinkedQueue<>();
        try {
            for (int index = 0; index < 32; index++) {
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        service.consume(request, "203.0.113.8");
                        successes.incrementAndGet();
                    } catch (BusinessException error) {
                        if (error.getCode() != 410) {
                            unexpected.add(error);
                        }
                    } catch (Throwable error) {
                        unexpected.add(error);
                    }
                });
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
            assertEquals(1, successes.get());
            assertTrue(unexpected.isEmpty(), () -> "unexpected=" + unexpected);
        } finally {
            start.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void mismatchedRecoveryBindingBurnsTheTicket() {
        // 【测什么】真实恢复 Lua 遇到同 requestId 的错身份会原子烧票，随后正确身份也只能 miss。
        // 【怎么算红】若只比较 requestId 不比较 binding，错误身份会拿到 ticket；若不 DEL，正确恢复仍命中。
        SentSession sent = sendSession("alice", "alice@example.com");

        BusinessException mismatch = assertThrows(BusinessException.class, () -> service.recover(
                sent.requestId(), "mallory", sent.email(), "INVITE", "203.0.113.9"
        ));

        assertEquals(400, mismatch.getCode());
        assertNull(service.recover(
                sent.requestId(), "alice", sent.email(), "INVITE", "203.0.113.8"
        ));
    }

    @Test
    void fifthWrongCodeExhaustsTheSession() {
        // 【测什么】真实 Redis 对同一 ticket 累计五次错码，第五次返回耗尽并删除会话。
        // 【怎么算红】若 attempts 非原子、阈值偏移或第五次不 DEL，错误码/随后正确码断言会变红。
        SentSession sent = sendSession("alice", "alice@example.com");
        String wrongCode = sent.code().equals("000000") ? "000001" : "000000";
        RegisterRequest wrong = registerRequest(sent, "alice", wrongCode);

        for (int attempt = 1; attempt <= 5; attempt++) {
            BusinessException error = assertThrows(
                    BusinessException.class,
                    () -> service.consume(wrong, "203.0.113.8")
            );
            assertEquals(attempt < 5 ? 400 : 410, error.getCode(), "attempt=" + attempt);
        }
        BusinessException exhausted = assertThrows(BusinessException.class, () -> service.consume(
                registerRequest(sent, "alice", sent.code()),
                "203.0.113.8"
        ));
        assertEquals(410, exhausted.getCode());
    }

    @Test
    void resendInvalidatesTheOldCodeAndKeepsAnAlmostSixtySecondCooldown() {
        // 【测什么】真实 Redis 初发冷却接近 60 秒；重发换码后旧码失败、新码成功。
        // 【怎么算红】INIT 参数错位成 600ms、reserve 未替换 code_mac 或激活旧值时，本测试会变红。
        SentSession sent = sendSession("alice", "alice@example.com");
        EmailCodeResponse recovered = service.recover(
                sent.requestId(), "alice", sent.email(), "INVITE", "203.0.113.8"
        );
        assertNotNull(recovered);
        assertTrue(recovered.resendAfterSeconds() >= 55);
        assertTrue(recovered.resendAfterSeconds() <= 60);

        EmailCodeResponse resent = forceResend(sent.response(), sent.email());
        String currentCode = deliveredCodes.get(deliveredCodes.size() - 1);
        if (currentCode.equals(sent.code())) {
            resent = forceResend(resent, sent.email());
            currentCode = deliveredCodes.get(deliveredCodes.size() - 1);
        }
        assertNotEquals(sent.code(), currentCode, "随机重发两次仍撞出旧码");
        EmailCodeResponse finalResponse = resent;
        String finalCode = currentCode;

        BusinessException oldCode = assertThrows(BusinessException.class, () -> service.consume(
                registerRequest(sent.withResponse(finalResponse), "alice", sent.code()),
                "203.0.113.8"
        ));
        assertEquals(400, oldCode.getCode());
        var verified = service.consume(
                registerRequest(sent.withResponse(finalResponse), "alice", finalCode),
                "203.0.113.8"
        );
        assertEquals("alice", verified.username());
        assertEquals(sent.email(), verified.email());
    }

    @Test
    void concurrentInitLoserCannotDeleteThePendingWinner() throws Exception {
        // 【测什么】相同 requestId 的 INIT 输家看见 PENDING 只返回 429，不清理赢家；赢家随后可激活恢复。
        // 【怎么算红】若 INIT=0 异常路径无条件 DEL，释放 SMTP 后赢家激活失败或恢复 miss。
        CountDownLatch smtpEntered = new CountDownLatch(1);
        CountDownLatch allowSmtp = new CountDownLatch(1);
        doAnswer(invocation -> {
            deliveredCodes.add(extractCode(invocation.getArgument(0)));
            smtpEntered.countDown();
            if (!allowSmtp.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test SMTP latch timed out");
            }
            return null;
        }).when(mailSender).send(any(MimeMessage.class));
        String requestId = UUID.randomUUID().toString();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            var winner = pool.submit(() -> service.send(
                    requestId, verified("alice"), "alice@example.com", "INVITE"
            ));
            assertTrue(smtpEntered.await(5, TimeUnit.SECONDS));

            BusinessException loser = assertThrows(BusinessException.class, () -> service.send(
                    requestId, verified("alice"), "alice@example.com", "INVITE"
            ));
            assertEquals(429, loser.getCode());
            allowSmtp.countDown();
            EmailCodeResponse sent = winner.get(5, TimeUnit.SECONDS);
            EmailCodeResponse recoveredWinner = service.recover(
                    requestId, "alice", "alice@example.com", "INVITE", "203.0.113.8"
            );
            assertNotNull(recoveredWinner);
            assertEquals(sent.registrationTicket(), recoveredWinner.registrationTicket());
        } finally {
            allowSmtp.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void lostActivationResponseCannotBeCleanedAfterTheSessionIsActive() {
        // 【测什么】ACTIVATE 已在真实 Redis 成功但响应丢失时，条件 cleanup 不得删除 ACTIVE 会话。
        // 【怎么算红】若 cleanup 退回无条件 DEL，503 后用同 requestId 恢复会得到 miss 而变红。
        StringRedisTemplate responseLosingRedis = spy(new StringRedisTemplate(connectionFactory));
        responseLosingRedis.afterPropertiesSet();
        AtomicInteger scripts = new AtomicInteger();
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            if (scripts.incrementAndGet() == 2) {
                throw new RedisConnectionFailureException("activation response lost");
            }
            return result;
        }).when(responseLosingRedis).execute(
                any(RedisScript.class),
                any(),
                any(Object[].class)
        );
        RegistrationEmailSessionService responseLosingService = newService(
                responseLosingRedis,
                mailSender
        );
        String requestId = UUID.randomUUID().toString();

        BusinessException uncertain = assertThrows(BusinessException.class, () -> responseLosingService.send(
                requestId, verified("alice"), "alice@example.com", "INVITE"
        ));

        assertEquals(503, uncertain.getCode());
        EmailCodeResponse recovered = service.recover(
                requestId, "alice", "alice@example.com", "INVITE", "203.0.113.8"
        );
        assertNotNull(recovered);
    }

    private RegistrationEmailSessionService newService(JavaMailSender sender) {
        return newService(redis, sender);
    }

    private RegistrationEmailSessionService newService(
            StringRedisTemplate template,
            JavaMailSender sender
    ) {
        TokenBucketRateLimitService limits = mock(TokenBucketRateLimitService.class);
        when(limits.tryAcquireDistributed(anyString(), any())).thenReturn(RateLimitResult.permitted());
        AppUserMapper users = mock(AppUserMapper.class);
        InviteCodeMapper invites = mock(InviteCodeMapper.class);
        when(users.selectCount(any())).thenReturn(0L);
        when(invites.selectCount(any())).thenReturn(1L);
        @SuppressWarnings("unchecked")
        ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(sender);
        return new RegistrationEmailSessionService(
                template,
                limits,
                new RateLimitConfig(),
                users,
                invites,
                provider,
                keyPrefix,
                "no-reply@example.com"
        );
    }

    private SentSession sendSession(String username, String email) {
        int codeIndex = deliveredCodes.size();
        String requestId = UUID.randomUUID().toString();
        EmailCodeResponse response = service.send(
                requestId,
                verified(username),
                email,
                "INVITE"
        );
        assertEquals(codeIndex + 1, deliveredCodes.size());
        return new SentSession(requestId, email, response, deliveredCodes.get(codeIndex));
    }

    private EmailCodeResponse forceResend(EmailCodeResponse current, String email) {
        redis.opsForHash().put(sessionKey(current.registrationTicket()), "next_send_at", "0");
        return service.resend(
                current.registrationTicket(),
                email,
                "203.0.113.8"
        );
    }

    private void captureCodes(JavaMailSender sender) {
        when(sender.createMimeMessage()).thenAnswer(invocation ->
                new MimeMessage(Session.getInstance(new Properties()))
        );
        doAnswer(invocation -> {
            deliveredCodes.add(extractCode(invocation.getArgument(0)));
            return null;
        }).when(sender).send(any(MimeMessage.class));
    }

    private static String extractCode(MimeMessage message) throws Exception {
        Matcher matcher = MAIL_CODE.matcher(extractText(message));
        if (!matcher.find()) {
            throw new AssertionError("邮件正文没有 6 位验证码");
        }
        return matcher.group(1);
    }

    private static String extractText(Part part) throws Exception {
        Object content = part.getContent();
        if (content instanceof String text) {
            return text;
        }
        if (content instanceof Multipart multipart) {
            StringBuilder combined = new StringBuilder();
            for (int index = 0; index < multipart.getCount(); index++) {
                combined.append(extractText(multipart.getBodyPart(index)));
            }
            return combined.toString();
        }
        return "";
    }

    private static CaptchaSecurityService.VerifiedAttempt verified(String username) {
        CaptchaSecurityService.VerifiedAttempt verified = mock(CaptchaSecurityService.VerifiedAttempt.class);
        when(verified.scene()).thenReturn(CaptchaScene.REGISTER);
        when(verified.username()).thenReturn(username);
        return verified;
    }

    private static RegisterRequest registerRequest(SentSession sent, String username, String code) {
        return new RegisterRequest(
                username,
                sent.email(),
                "INVITE",
                "secret1",
                sent.response().registrationTicket(),
                code
        );
    }

    private String sessionKey(String ticket) {
        return keyPrefix + ":session:" + sha256(ticket);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                    value.getBytes(StandardCharsets.UTF_8)
            ));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private record SentSession(
            String requestId,
            String email,
            EmailCodeResponse response,
            String code
    ) {
        private SentSession withResponse(EmailCodeResponse changedResponse) {
            return new SentSession(requestId, email, changedResponse, code);
        }
    }
}
