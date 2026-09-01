package org.example.seedancegenarate.service;

import com.anji.captcha.model.common.ResponseModel;
import com.anji.captcha.model.vo.CaptchaVO;
import com.anji.captcha.service.CaptchaService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.seedancegenarate.config.RateLimitConfig;
import org.example.seedancegenarate.dto.CaptchaPayloads.CaptchaCheckRequest;
import org.example.seedancegenarate.dto.CaptchaPayloads.CaptchaCheckResponse;
import org.example.seedancegenarate.dto.CaptchaPayloads.CaptchaGetRequest;
import org.example.seedancegenarate.dto.CaptchaPayloads.CaptchaGetResponse;
import org.example.seedancegenarate.dto.CaptchaPayloads.CaptchaScene;
import org.example.seedancegenarate.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/** AJ challenge 与登录/注册一次性 proof 的唯一服务端边界。 */
@Service
public class CaptchaSecurityService {
    private static final String CAPTCHA_TYPE = "blockPuzzle";
    private static final long CHALLENGE_TTL_SECONDS = 120;
    private static final int CLIENT_UID_MAX = 128;
    private static final int TOKEN_MAX = 128;
    private static final int POINT_JSON_MAX = 4096;
    private static final int USERNAME_MAX = 128;
    private static final int PROOF_MAX = 128;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private static final DefaultRedisScript<Long> CONSUME_PROOF_SCRIPT = new DefaultRedisScript<>("""
            local value = redis.call('GET', KEYS[1])
            if not value then
                return 0
            end
            redis.call('DEL', KEYS[1])
            if value == ARGV[1] then
                return 1
            end
            return -1
            """, Long.class);

    private final CaptchaService ajCaptchaService;
    private final StringRedisTemplate redisTemplate;
    private final TokenBucketRateLimitService rateLimitService;
    private final RateLimitConfig rateLimitConfig;
    private final ObjectMapper objectMapper;
    private final String redisKeyPrefix;
    private final long proofTtlSeconds;

    public CaptchaSecurityService(
            CaptchaService ajCaptchaService,
            StringRedisTemplate redisTemplate,
            TokenBucketRateLimitService rateLimitService,
            RateLimitConfig rateLimitConfig,
            ObjectMapper objectMapper,
            @Value("${captcha.redis-key-prefix:local:seedance:captcha}") String redisKeyPrefix,
            @Value("${captcha.proof-ttl-seconds:120}") long proofTtlSeconds
    ) {
        this.ajCaptchaService = ajCaptchaService;
        this.redisTemplate = redisTemplate;
        this.rateLimitService = rateLimitService;
        this.rateLimitConfig = rateLimitConfig;
        this.objectMapper = objectMapper;
        this.redisKeyPrefix = requirePrefix(redisKeyPrefix);
        if (proofTtlSeconds < 30 || proofTtlSeconds > 300) {
            throw new IllegalStateException("captcha.proof-ttl-seconds 必须在 30 到 300 秒之间");
        }
        this.proofTtlSeconds = proofTtlSeconds;
    }

    public CaptchaGetResponse issueChallenge(CaptchaGetRequest request, String clientIp) {
        requireAllowed(
                "captcha:get:ip:" + hash(normalizeIp(clientIp)),
                rateLimitConfig.getCaptchaGetIp(),
                "验证码获取过于频繁，请稍后再试"
        );
        requireType(request == null ? null : request.captchaType());
        String clientUid = requireText(request.clientUid(), CLIENT_UID_MAX, "clientUid");

        try {
            CaptchaVO input = new CaptchaVO();
            input.setCaptchaType(CAPTCHA_TYPE);
            input.setClientUid(clientUid);
            ResponseModel result = ajCaptchaService.get(input);
            CaptchaVO data = successfulChallengeData(result);
            String token = requireAjField(data.getToken(), "token");
            String original = requireAjField(data.getOriginalImageBase64(), "originalImageBase64");
            String jigsaw = requireAjField(data.getJigsawImageBase64(), "jigsawImageBase64");
            if (StringUtils.hasText(data.getSecretKey())) {
                throw new IllegalStateException("AJ-Captcha AES 未关闭");
            }
            return new CaptchaGetResponse(
                    token, original, jigsaw, "", CHALLENGE_TTL_SECONDS
            );
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw unavailable(e);
        }
    }

    public CaptchaCheckResponse checkChallenge(CaptchaCheckRequest request, String clientIp) {
        requireAllowed(
                "captcha:check:ip:" + hash(normalizeIp(clientIp)),
                rateLimitConfig.getCaptchaCheckIp(),
                "验证码校验过于频繁，请稍后再试"
        );
        ValidCheck valid = validateCheck(request);
        try {
            Boolean claimed = redisTemplate.opsForValue().setIfAbsent(
                    key("challenge-claim:" + hash(valid.token())),
                    "1",
                    Duration.ofSeconds(CHALLENGE_TTL_SECONDS)
            );
            if (claimed == null) {
                throw new IllegalStateException("Redis challenge 占位返回空");
            }
            if (!claimed) {
                throw invalidCaptcha();
            }

            CaptchaVO input = new CaptchaVO();
            input.setCaptchaType(CAPTCHA_TYPE);
            input.setClientUid(valid.clientUid());
            input.setToken(valid.token());
            input.setPointJson(valid.pointJson());
            ResponseModel checked = ajCaptchaService.check(input);
            CaptchaVO checkedData = successfulData(checked);
            if (!Boolean.TRUE.equals(checkedData.getResult())) {
                throw invalidCaptcha();
            }

            String proof = newProof();
            redisTemplate.opsForValue().set(
                    proofKey(proof),
                    binding(valid.scene(), valid.username()),
                    Duration.ofSeconds(proofTtlSeconds)
            );
            return new CaptchaCheckResponse(proof, proofTtlSeconds);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw unavailable(e);
        }
    }

    public VerifiedAttempt consumeProof(
            CaptchaScene scene,
            String username,
            String captchaProof,
            String clientIp
    ) {
        String normalizedIp = normalizeIp(clientIp);
        requireAuthIpLimit(scene, normalizedIp);
        String normalizedUsername = normalizeUsername(username);
        String proof = requireProof(captchaProof);
        try {
            Long consumed = redisTemplate.execute(
                    CONSUME_PROOF_SCRIPT,
                    List.of(proofKey(proof)),
                    binding(scene, normalizedUsername)
            );
            if (consumed == null) {
                throw new IllegalStateException("Redis proof 消费脚本返回空");
            }
            if (consumed != 1L) {
                throw invalidCaptcha();
            }
            requireAuthUsernameLimit(scene, normalizedUsername);
            return new VerifiedAttempt(scene, normalizedUsername);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw unavailable(e);
        }
    }

    private void requireAuthIpLimit(CaptchaScene scene, String clientIp) {
        if (scene == null) {
            throw invalidCaptcha();
        }
        String scope = scene == CaptchaScene.LOGIN ? "login" : "register";
        RateLimitConfig.Bucket ipBucket = scene == CaptchaScene.LOGIN
                ? rateLimitConfig.getLoginIp()
                : rateLimitConfig.getRegisterIp();
        requireAllowed(
                "auth:" + scope + ":ip:" + hash(clientIp),
                ipBucket,
                "登录或注册尝试过于频繁，请稍后再试"
        );
    }

    private void requireAuthUsernameLimit(CaptchaScene scene, String username) {
        String scope = scene == CaptchaScene.LOGIN ? "login" : "register";
        RateLimitConfig.Bucket usernameBucket = scene == CaptchaScene.LOGIN
                ? rateLimitConfig.getLoginUsername()
                : rateLimitConfig.getRegisterUsername();
        requireAllowed(
                "auth:" + scope + ":username:" + hash(username.toLowerCase(Locale.ROOT)),
                usernameBucket,
                "登录或注册尝试过于频繁，请稍后再试"
        );
    }

    private void requireAllowed(String key, RateLimitConfig.Bucket bucket, String message) {
        try {
            RateLimitResult result = rateLimitService.tryAcquireDistributed(key, bucket);
            if (!result.allowed()) {
                throw new BusinessException(429, message);
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw unavailable(e);
        }
    }

    private ValidCheck validateCheck(CaptchaCheckRequest request) {
        if (request == null) {
            throw BusinessException.badRequest("验证码请求不能为空");
        }
        requireType(request.captchaType());
        String clientUid = requireText(request.clientUid(), CLIENT_UID_MAX, "clientUid");
        String token = requireText(request.token(), TOKEN_MAX, "token");
        if (!token.matches("[0-9a-fA-F]{32}")) {
            throw BusinessException.badRequest("验证码 token 格式不合法");
        }
        String pointJson = requireText(request.pointJson(), POINT_JSON_MAX, "pointJson");
        validatePoint(pointJson);
        if (request.scene() == null) {
            throw BusinessException.badRequest("验证码场景不合法");
        }
        String username = normalizeUsername(request.username());
        return new ValidCheck(clientUid, token, pointJson, request.scene(), username);
    }

    private void validatePoint(String pointJson) {
        try {
            JsonNode point = objectMapper.readTree(pointJson);
            if (!point.isObject()
                    || point.size() != 2
                    || !point.path("x").isIntegralNumber()
                    || !point.path("y").isIntegralNumber()
                    || !point.path("x").canConvertToInt()
                    || !point.path("y").canConvertToInt()) {
                throw BusinessException.badRequest("验证码坐标格式不合法");
            }
            int x = point.path("x").intValue();
            int y = point.path("y").intValue();
            if (x < 0 || x > 1000 || y < 0 || y > 1000) {
                throw BusinessException.badRequest("验证码坐标格式不合法");
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw BusinessException.badRequest("验证码坐标格式不合法");
        }
    }

    private static CaptchaVO successfulData(ResponseModel result) {
        if (result == null || !result.isSuccess() || !(result.getRepData() instanceof CaptchaVO data)) {
            throw invalidCaptcha();
        }
        return data;
    }

    private static CaptchaVO successfulChallengeData(ResponseModel result) {
        if (result == null || !result.isSuccess() || !(result.getRepData() instanceof CaptchaVO data)) {
            throw unavailable(new IllegalStateException("AJ-Captcha challenge 生成失败"));
        }
        return data;
    }

    private static void requireType(String captchaType) {
        if (!CAPTCHA_TYPE.equals(captchaType)) {
            throw BusinessException.badRequest("仅支持 blockPuzzle 验证码");
        }
    }

    private static String requireText(String value, int maxLength, String field) {
        if (!StringUtils.hasText(value) || value.length() > maxLength) {
            throw BusinessException.badRequest(field + " 格式不合法");
        }
        return value.trim();
    }

    private static String requireAjField(String value, String field) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException("AJ-Captcha 未返回 " + field);
        }
        return value;
    }

    private static String normalizeUsername(String username) {
        if (!StringUtils.hasText(username) || username.length() > USERNAME_MAX) {
            throw invalidCaptcha();
        }
        return username.trim();
    }

    private static String normalizeIp(String clientIp) {
        if (!StringUtils.hasText(clientIp) || clientIp.trim().length() > 64) {
            return "unknown";
        }
        return clientIp.trim();
    }

    private static String requireProof(String proof) {
        if (!StringUtils.hasText(proof)
                || proof.length() > PROOF_MAX
                || !proof.matches("[A-Za-z0-9_-]{32,128}")) {
            throw invalidCaptcha();
        }
        return proof;
    }

    private static String binding(CaptchaScene scene, String username) {
        return scene.name() + ":" + hash(username);
    }

    private String proofKey(String proof) {
        return key("proof:" + hash(proof));
    }

    private String key(String suffix) {
        return redisKeyPrefix + ":security:" + suffix;
    }

    private static String newProof() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static String requirePrefix(String prefix) {
        if (!StringUtils.hasText(prefix)) {
            throw new IllegalStateException("captcha.redis-key-prefix 不能为空");
        }
        return prefix.trim().replaceAll(":+$", "");
    }

    private static BusinessException invalidCaptcha() {
        return BusinessException.badRequest("验证码已失效，请重新验证");
    }

    private static BusinessException unavailable(Exception cause) {
        return new BusinessException(503, "验证码服务暂不可用，请稍后重试", cause);
    }

    private record ValidCheck(
            String clientUid,
            String token,
            String pointJson,
            CaptchaScene scene,
            String username
    ) {
    }

    public static final class VerifiedAttempt {
        private final CaptchaScene scene;
        private final String username;

        private VerifiedAttempt(CaptchaScene scene, String username) {
            this.scene = scene;
            this.username = username;
        }

        public CaptchaScene scene() {
            return scene;
        }

        public String username() {
            return username;
        }
    }
}
