package org.example.seedancegenarate.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.example.seedancegenarate.config.RateLimitConfig;
import org.example.seedancegenarate.dto.CaptchaPayloads.CaptchaScene;
import org.example.seedancegenarate.dto.RegistrationPayloads.EmailCodeResponse;
import org.example.seedancegenarate.dto.RegistrationPayloads.RegisterRequest;
import org.example.seedancegenarate.entity.AppUser;
import org.example.seedancegenarate.entity.InviteCode;
import org.example.seedancegenarate.exception.BusinessException;
import org.example.seedancegenarate.mapper.AppUserMapper;
import org.example.seedancegenarate.mapper.InviteCodeMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** 一次性行为 proof 与最终注册之间的短期邮箱验证会话。 */
@Slf4j
@Service
public final class RegistrationEmailSessionService {
    private static final long SESSION_TTL_SECONDS = 600;
    private static final long RESEND_AFTER_SECONDS = 60;
    private static final int MAX_SENDS = 3;
    private static final int MAX_ATTEMPTS = 5;
    private static final int USERNAME_MAX = 64;
    private static final int EMAIL_MAX = 254;
    private static final int INVITE_MAX = 32;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private static final DefaultRedisScript<Long> INIT_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[1]) == 1 then return 0 end
            local time = redis.call('TIME')
            local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
            redis.call('HSET', KEYS[1],
                'state', 'PENDING',
                'binding', ARGV[1],
                'email_hash', ARGV[2],
                'code_mac', ARGV[3],
                'attempts', 0,
                'sends', 1,
                'next_send_at', now + tonumber(ARGV[5]))
            redis.call('EXPIRE', KEYS[1], ARGV[4])
            return 1
            """, Long.class);

    private static final DefaultRedisScript<Long> ACTIVATE_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('HGET', KEYS[1], 'state') ~= 'PENDING' then return 0 end
            if redis.call('HGET', KEYS[1], 'code_mac') ~= ARGV[1] then return 0 end
            redis.call('HSET', KEYS[1], 'state', 'ACTIVE')
            return redis.call('TTL', KEYS[1])
            """, Long.class);

    @SuppressWarnings("rawtypes")
    private static final DefaultRedisScript<List> RECOVER_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[1]) == 0 then return {0} end
            if redis.call('HGET', KEYS[1], 'binding') ~= ARGV[1] then
                redis.call('DEL', KEYS[1])
                return {-2}
            end
            local state = redis.call('HGET', KEYS[1], 'state')
            if state == 'PENDING' then return {-1} end
            if state ~= 'ACTIVE' then
                redis.call('DEL', KEYS[1])
                return {0}
            end
            local ttl = redis.call('TTL', KEYS[1])
            if ttl <= 0 then
                redis.call('DEL', KEYS[1])
                return {0}
            end
            local time = redis.call('TIME')
            local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
            local nextSendAt = tonumber(redis.call('HGET', KEYS[1], 'next_send_at') or '0')
            local remaining = nextSendAt - now
            local cooldown = 0
            if remaining > 0 then cooldown = math.floor((remaining + 999) / 1000) end
            return {1, ttl, cooldown}
            """, List.class);

    private static final DefaultRedisScript<Long> CLEANUP_PENDING_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('HGET', KEYS[1], 'state') ~= 'PENDING' then return 0 end
            if redis.call('HGET', KEYS[1], 'code_mac') ~= ARGV[1] then return 0 end
            return redis.call('DEL', KEYS[1])
            """, Long.class);

    private static final DefaultRedisScript<Long> CHECK_RESEND_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[1]) == 0 then return 0 end
            if redis.call('HGET', KEYS[1], 'state') ~= 'ACTIVE' then return -1 end
            if redis.call('HGET', KEYS[1], 'email_hash') ~= ARGV[1] then
                redis.call('DEL', KEYS[1])
                return -2
            end
            return 1
            """, Long.class);

    private static final DefaultRedisScript<Long> RESERVE_RESEND_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[1]) == 0 then return 0 end
            if redis.call('HGET', KEYS[1], 'state') ~= 'ACTIVE' then return -1 end
            if redis.call('HGET', KEYS[1], 'email_hash') ~= ARGV[1] then
                redis.call('DEL', KEYS[1])
                return -2
            end
            local sends = tonumber(redis.call('HGET', KEYS[1], 'sends') or '0')
            if sends >= tonumber(ARGV[4]) then
                redis.call('DEL', KEYS[1])
                return -3
            end
            local time = redis.call('TIME')
            local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
            local nextSendAt = tonumber(redis.call('HGET', KEYS[1], 'next_send_at') or '0')
            if nextSendAt > now then return -4 end
            redis.call('HSET', KEYS[1],
                'state', 'PENDING',
                'code_mac', ARGV[2],
                'attempts', 0,
                'sends', sends + 1,
                'next_send_at', now + tonumber(ARGV[3]))
            return redis.call('TTL', KEYS[1])
            """, Long.class);

    private static final DefaultRedisScript<Long> CONSUME_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[1]) == 0 then return 0 end
            if redis.call('HGET', KEYS[1], 'state') ~= 'ACTIVE' then return -1 end
            if redis.call('HGET', KEYS[1], 'binding') ~= ARGV[1] then
                redis.call('DEL', KEYS[1])
                return -2
            end
            if redis.call('HGET', KEYS[1], 'code_mac') ~= ARGV[2] then
                local attempts = redis.call('HINCRBY', KEYS[1], 'attempts', 1)
                if attempts >= tonumber(ARGV[3]) then
                    redis.call('DEL', KEYS[1])
                    return -4
                end
                return -3
            end
            redis.call('DEL', KEYS[1])
            return 1
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final TokenBucketRateLimitService rateLimitService;
    private final RateLimitConfig rateLimitConfig;
    private final AppUserMapper appUserMapper;
    private final InviteCodeMapper inviteCodeMapper;
    private final ObjectProvider<JavaMailSender> mailSenderProvider;
    private final String redisKeyPrefix;
    private final String fromAddress;

    public RegistrationEmailSessionService(
            StringRedisTemplate redisTemplate,
            TokenBucketRateLimitService rateLimitService,
            RateLimitConfig rateLimitConfig,
            AppUserMapper appUserMapper,
            InviteCodeMapper inviteCodeMapper,
            ObjectProvider<JavaMailSender> mailSenderProvider,
            @Value("${registration.email.redis-key-prefix:local:seedance:registration-email}") String redisKeyPrefix,
            @Value("${registration.email.from:${spring.mail.from:${spring.mail.username:}}}") String fromAddress
    ) {
        this.redisTemplate = redisTemplate;
        this.rateLimitService = rateLimitService;
        this.rateLimitConfig = rateLimitConfig;
        this.appUserMapper = appUserMapper;
        this.inviteCodeMapper = inviteCodeMapper;
        this.mailSenderProvider = mailSenderProvider;
        this.redisKeyPrefix = requirePrefix(redisKeyPrefix);
        this.fromAddress = fromAddress == null ? "" : fromAddress.trim();
    }

    public EmailCodeResponse recover(
            String requestId,
            String username,
            String email,
            String inviteCode,
            String clientIp
    ) {
        String canonicalRequestId = requireRequestId(requestId);
        String normalizedUsername = normalizeUsername(username);
        String normalizedEmail = normalizeEmail(email);
        String normalizedInvite = normalizeInvite(inviteCode);
        requireMailIpLimit(clientIp);
        return recoverExisting(
                canonicalRequestId,
                normalizedUsername,
                normalizedEmail,
                normalizedInvite
        );
    }

    private EmailCodeResponse recoverExisting(
            String canonicalRequestId,
            String normalizedUsername,
            String normalizedEmail,
            String normalizedInvite
    ) {
        String ticket = ticketForRequestId(canonicalRequestId);
        List<?> recovered;
        try {
            recovered = redisTemplate.execute(
                    RECOVER_SCRIPT,
                    List.of(sessionKey(ticket)),
                    binding(normalizedUsername, normalizedEmail, normalizedInvite)
            );
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw unavailable(e);
        }
        if (recovered == null || recovered.isEmpty()) {
            throw unavailable(new IllegalStateException("Redis 注册会话恢复返回空"));
        }
        long state = asLong(recovered.get(0));
        if (state == 1L) {
            if (recovered.size() != 3) {
                throw unavailable(new IllegalStateException("Redis 注册会话恢复返回格式错误"));
            }
            return new EmailCodeResponse(
                    ticket,
                    maskEmail(normalizedEmail),
                    asLong(recovered.get(1)),
                    asLong(recovered.get(2))
            );
        }
        if (state == 0L) {
            return null;
        }
        if (state == -1L) {
            throw new BusinessException(429, "邮箱验证码正在发送，请稍后再试");
        }
        if (state == -2L) {
            throw BusinessException.badRequest("注册信息已变更，请重新验证");
        }
        throw unavailable(new IllegalStateException("Redis 注册会话恢复返回未知状态"));
    }

    public EmailCodeResponse send(
            String requestId,
            CaptchaSecurityService.VerifiedAttempt verified,
            String email,
            String inviteCode
    ) {
        String canonicalRequestId = requireRequestId(requestId);
        if (verified == null || verified.scene() != CaptchaScene.REGISTER) {
            throw BusinessException.badRequest("注册验证已失效，请重新验证");
        }
        String username = normalizeUsername(verified.username());
        String normalizedEmail = normalizeEmail(email);
        String normalizedInvite = normalizeInvite(inviteCode);
        requireMailAddressAndGlobalLimits(normalizedEmail);
        requireAvailableRegistration(username, normalizedEmail, normalizedInvite);

        String ticket = ticketForRequestId(canonicalRequestId);
        String sessionKey = sessionKey(ticket);
        String code = newCode();
        String codeMac = codeMac(ticket, code);
        String binding = binding(username, normalizedEmail, normalizedInvite);
        String emailHash = hash(normalizedEmail);
        try {
            Long initialized = redisTemplate.execute(
                    INIT_SCRIPT,
                    List.of(sessionKey),
                    binding,
                    emailHash,
                    codeMac,
                    Long.toString(SESSION_TTL_SECONDS),
                    Long.toString(RESEND_AFTER_SECONDS * 1000)
            );
            if (initialized == null) {
                throw new IllegalStateException("Redis 注册会话初始化返回空");
            }
            if (initialized == 0L) {
                EmailCodeResponse recovered = recoverExisting(
                        canonicalRequestId,
                        username,
                        normalizedEmail,
                        normalizedInvite
                );
                if (recovered != null) {
                    return recovered;
                }
                throw expired();
            }
            if (initialized != 1L) {
                throw new IllegalStateException("Redis 注册会话初始化返回未知状态");
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            cleanupPending(sessionKey, codeMac);
            throw unavailable(e);
        }

        return deliverAndActivate(ticket, sessionKey, normalizedEmail, code, codeMac);
    }

    public EmailCodeResponse resend(String ticket, String email, String clientIp) {
        String validTicket = requireTicket(ticket);
        String normalizedEmail = normalizeEmail(email);
        String sessionKey = sessionKey(validTicket);
        requireMailIpLimit(clientIp);
        requireResendBinding(sessionKey, normalizedEmail);
        requireMailAddressAndGlobalLimits(normalizedEmail);
        String code = newCode();
        String codeMac = codeMac(validTicket, code);
        long ttl;
        try {
            Long reserved = redisTemplate.execute(
                    RESERVE_RESEND_SCRIPT,
                    List.of(sessionKey),
                    hash(normalizedEmail),
                    codeMac,
                    Long.toString(RESEND_AFTER_SECONDS * 1000),
                    Integer.toString(MAX_SENDS)
            );
            if (reserved == null) {
                throw new IllegalStateException("Redis 重发预留返回空");
            }
            ttl = reserved;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw unavailable(e);
        }
        if (ttl == 0 || ttl == -3) {
            throw expired();
        }
        if (ttl == -1 || ttl == -4) {
            throw new BusinessException(429, "请稍后再发送邮箱验证码");
        }
        if (ttl == -2) {
            throw BusinessException.badRequest("注册信息已变更，请重新验证");
        }
        if (ttl < 0) {
            throw unavailable(new IllegalStateException("Redis 重发返回未知状态"));
        }
        return deliverAndActivate(validTicket, sessionKey, normalizedEmail, code, codeMac);
    }

    public VerifiedRegistration consume(RegisterRequest request, String clientIp) {
        if (request == null) {
            throw BusinessException.badRequest("注册请求不能为空");
        }
        String username = normalizeUsername(request.username());
        String normalizedEmail = normalizeEmail(request.email());
        String normalizedInvite = normalizeInvite(request.inviteCode());
        requirePassword(request.password());
        String ticket = requireTicket(request.registrationTicket());
        String emailCode = requireEmailCode(request.emailCode());
        String sessionKey = sessionKey(ticket);
        long consumed;
        try {
            Long result = redisTemplate.execute(
                    CONSUME_SCRIPT,
                    List.of(sessionKey),
                    binding(username, normalizedEmail, normalizedInvite),
                    codeMac(ticket, emailCode),
                    Integer.toString(MAX_ATTEMPTS)
            );
            if (result == null) {
                throw new IllegalStateException("Redis 注册会话消费返回空");
            }
            consumed = result;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw unavailable(e);
        }
        if (consumed == 1L) {
            return new VerifiedRegistration(username, normalizedEmail, normalizedInvite);
        }
        if (consumed == 0 || consumed == -4) {
            throw expired();
        }
        if (consumed == -1) {
            throw new BusinessException(429, "邮箱验证码正在发送，请稍后再试");
        }
        if (consumed == -2) {
            throw BusinessException.badRequest("注册信息已变更，请重新验证");
        }
        if (consumed == -3) {
            throw BusinessException.badRequest("邮箱验证码错误");
        }
        throw unavailable(new IllegalStateException("Redis 注册会话返回未知状态"));
    }

    private EmailCodeResponse deliverAndActivate(
            String ticket,
            String sessionKey,
            String normalizedEmail,
            String code,
            String codeMac
    ) {
        try {
            sendMail(normalizedEmail, code);
        } catch (Exception e) {
            cleanupPending(sessionKey, codeMac);
            throw unavailable(e);
        }

        long ttl;
        try {
            Long activated = redisTemplate.execute(
                    ACTIVATE_SCRIPT,
                    List.of(sessionKey),
                    codeMac
            );
            if (activated == null || activated <= 0) {
                throw new IllegalStateException("Redis 注册会话激活失败");
            }
            ttl = activated;
        } catch (Exception e) {
            cleanupPending(sessionKey, codeMac);
            throw unavailable(e);
        }
        return new EmailCodeResponse(ticket, maskEmail(normalizedEmail), ttl, RESEND_AFTER_SECONDS);
    }

    private void sendMail(String email, String code) {
        JavaMailSender sender = mailSenderProvider.getIfAvailable();

        if (sender == null || !StringUtils.hasText(fromAddress)) {
            throw new IllegalStateException("邮件发送器未配置");
        }

        try {
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper =
                    new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());

            helper.setFrom(fromAddress, "Ascent创作");
            helper.setTo(email);
            helper.setSubject("Ascent创作｜注册验证码");

            String content = """
                <div style="background:#f5f7fb;padding:40px 20px;font-family:Arial,'Microsoft YaHei',sans-serif;">
                    <div style="max-width:520px;margin:auto;background:#ffffff;border-radius:12px;padding:32px;
                                box-shadow:0 4px 18px rgba(0,0,0,.08);">
                        <h2 style="color:#222;margin-top:0;">欢迎注册 Ascent创作</h2>

                        <p style="color:#666;font-size:15px;">
                            您正在进行账号注册，验证码如下：
                        </p>

                        <div style="margin:28px 0;padding:18px;text-align:center;
                                    background:#f0f5ff;border-radius:8px;">
                            <span style="font-size:34px;font-weight:bold;letter-spacing:8px;
                                         color:#3478f6;">%s</span>
                        </div>

                        <p style="color:#666;font-size:14px;">
                            验证码 <b>10分钟内有效</b>，请勿将验证码转发给他人。
                        </p>

                        <p style="color:#999;font-size:13px;margin-bottom:0;">
                            如果这不是您的操作，请忽略此邮件。
                        </p>
                    </div>
                </div>
                """.formatted(code);

            helper.setText(content, true);
            sender.send(message);

        } catch (Exception e) {
            log.error("注册邮箱验证码发送失败: to={}, fromAddress={}, error={}", email, fromAddress, e.getMessage(), e);
            throw new IllegalStateException("邮件发送失败: " + e.getMessage(), e);
        }
    }

    private void requireMailIpLimit(String clientIp) {
        requireAllowed(
                "registration-email:ip:" + hash(normalizeIp(clientIp)),
                rateLimitConfig.getRegisterEmailIp(),
                "邮箱验证码发送过于频繁，请稍后再试"
        );
    }

    private void requireMailAddressAndGlobalLimits(String email) {
        requireAllowed(
                "registration-email:address:" + hash(email),
                rateLimitConfig.getRegisterEmailAddress(),
                "该邮箱验证码发送过于频繁，请稍后再试"
        );
        requireAllowed(
                "registration-email:global",
                rateLimitConfig.getRegisterEmailGlobal(),
                "邮箱验证码发送繁忙，请稍后再试"
        );
    }

    private void requireResendBinding(String sessionKey, String email) {
        long checked;
        try {
            Long result = redisTemplate.execute(
                    CHECK_RESEND_SCRIPT,
                    List.of(sessionKey),
                    hash(email)
            );
            if (result == null) {
                throw new IllegalStateException("Redis 重发会话校验返回空");
            }
            checked = result;
        } catch (Exception e) {
            throw unavailable(e);
        }
        if (checked == 1L) {
            return;
        }
        if (checked == 0) {
            throw expired();
        }
        if (checked == -1) {
            throw new BusinessException(429, "邮箱验证码正在发送，请稍后再试");
        }
        if (checked == -2) {
            throw BusinessException.badRequest("注册信息已变更，请重新验证");
        }
        throw unavailable(new IllegalStateException("Redis 重发会话校验返回未知状态"));
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

    private void requireAvailableRegistration(String username, String email, String inviteCode) {
        if (appUserMapper.selectCount(
                Wrappers.<AppUser>lambdaQuery().eq(AppUser::getUsername, username)
        ) > 0) {
            throw BusinessException.conflict("用户名已存在");
        }
        if (appUserMapper.selectCount(
                Wrappers.<AppUser>lambdaQuery().eq(AppUser::getEmail, email)
        ) > 0) {
            throw BusinessException.conflict("邮箱已被注册");
        }
        if (inviteCodeMapper.selectCount(
                Wrappers.<InviteCode>lambdaQuery()
                        .eq(InviteCode::getCode, inviteCode)
                        .eq(InviteCode::getStatus, "UNUSED")
        ) != 1) {
            throw BusinessException.conflict("邀请码无效或已使用");
        }
    }

    private void cleanupPending(String sessionKey, String codeMac) {
        if (!StringUtils.hasText(sessionKey) || !StringUtils.hasText(codeMac)) {
            return;
        }
        try {
            redisTemplate.execute(
                    CLEANUP_PENDING_SCRIPT,
                    List.of(sessionKey),
                    codeMac
            );
        } catch (Exception ignored) {
            // 主错误仍按 503 返回；会话默认 PENDING 且不可消费，最终由 TTL 清理。
        }
    }

    private static String normalizeUsername(String value) {
        if (!StringUtils.hasText(value)) {
            throw BusinessException.badRequest("用户名不能为空");
        }
        String normalized = value.trim();
        if (normalized.length() < 3 || normalized.length() > USERNAME_MAX) {
            throw BusinessException.badRequest("用户名长度应为 3 到 64 个字符");
        }
        return normalized;
    }

    private static String normalizeInvite(String value) {
        if (!StringUtils.hasText(value)) {
            throw BusinessException.badRequest("请输入邀请码");
        }
        String normalized = value.trim();
        if (normalized.length() > INVITE_MAX) {
            throw BusinessException.badRequest("邀请码格式不合法");
        }
        return normalized;
    }

    private static String normalizeEmail(String value) {
        if (!StringUtils.hasText(value)) {
            throw BusinessException.badRequest("邮箱不能为空");
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        int at = normalized.indexOf('@');
        if (normalized.length() > EMAIL_MAX
                || at <= 0
                || at > 64
                || at != normalized.lastIndexOf('@')
                || !validDomain(normalized.substring(at + 1))) {
            throw BusinessException.badRequest("邮箱格式不合法");
        }
        try {
            InternetAddress parsed = new InternetAddress(normalized, true);
            parsed.validate();
            if (parsed.getPersonal() != null || !normalized.equals(parsed.getAddress())) {
                throw new IllegalArgumentException("邮箱包含显示名称");
            }
        } catch (Exception e) {
            throw BusinessException.badRequest("邮箱格式不合法");
        }
        return normalized;
    }

    private static boolean validDomain(String domain) {
        if (domain.length() > 253
                || domain.indexOf('.') <= 0
                || domain.startsWith(".")
                || domain.endsWith(".")) {
            return false;
        }
        for (String label : domain.split("\\.", -1)) {
            if (label.isEmpty()
                    || label.length() > 63
                    || !isAsciiLetterOrDigit(label.charAt(0))
                    || !isAsciiLetterOrDigit(label.charAt(label.length() - 1))) {
                return false;
            }
            for (int index = 1; index < label.length() - 1; index++) {
                char character = label.charAt(index);
                if (!isAsciiLetterOrDigit(character) && character != '-') {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean isAsciiLetterOrDigit(char value) {
        return value >= 'a' && value <= 'z' || value >= '0' && value <= '9';
    }

    private static void requirePassword(String value) {
        if (!StringUtils.hasText(value) || value.length() < 6) {
            throw BusinessException.badRequest("密码至少 6 个字符");
        }
    }

    private static String requireTicket(String value) {
        if (!StringUtils.hasText(value)) {
            throw BusinessException.badRequest("注册会话不能为空");
        }
        String ticket = value.trim();
        if (!ticket.matches("[A-Za-z0-9_-]{43}")) {
            throw BusinessException.badRequest("注册会话格式不合法");
        }
        return ticket;
    }

    private static String requireRequestId(String value) {
        if (!StringUtils.hasText(value)) {
            throw BusinessException.badRequest("发码请求标识不能为空");
        }
        try {
            UUID requestId = UUID.fromString(value);
            if (requestId.version() != 4
                    || requestId.variant() != 2
                    || !requestId.toString().equals(value)) {
                throw new IllegalArgumentException("requestId 不是 canonical UUID v4");
            }
            return value;
        } catch (IllegalArgumentException e) {
            throw BusinessException.badRequest("发码请求标识格式不合法");
        }
    }

    private static String requireEmailCode(String value) {
        if (value == null || !value.matches("[0-9]{6}")) {
            throw BusinessException.badRequest("邮箱验证码应为 6 位数字");
        }
        return value;
    }

    private static String normalizeIp(String value) {
        if (!StringUtils.hasText(value)) {
            return "unknown";
        }
        String normalized = value.trim();
        return normalized.length() > 64 ? normalized.substring(0, 64) : normalized;
    }

    private String sessionKey(String ticket) {
        return redisKeyPrefix + ":session:" + hash(ticket);
    }

    private static String binding(String username, String email, String inviteCode) {
        return hash(username + '\0' + email + '\0' + inviteCode);
    }

    private static String ticketForRequestId(String requestId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    ("registration-ticket\0" + requestId).getBytes(StandardCharsets.UTF_8)
            );
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException("注册 ticket 生成失败", e);
        }
    }

    private static String newCode() {
        return String.format(Locale.ROOT, "%06d", SECURE_RANDOM.nextInt(1_000_000));
    }

    private static String codeMac(String ticket, String code) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(ticket.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(code.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("验证码摘要生成失败", e);
        }
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (Exception e) {
            throw new IllegalStateException("摘要生成失败", e);
        }
    }

    private static long asLong(Object value) {
        try {
            if (value instanceof Number number) {
                return number.longValue();
            }
            if (value instanceof byte[] bytes) {
                return Long.parseLong(new String(bytes, StandardCharsets.UTF_8));
            }
            return Long.parseLong(String.valueOf(value));
        } catch (RuntimeException e) {
            throw unavailable(new IllegalStateException("Redis 注册会话恢复返回格式错误", e));
        }
    }

    private static String maskEmail(String email) {
        int at = email.indexOf('@');
        String local = email.substring(0, at);
        String domain = email.substring(at);
        if (local.length() == 1) {
            return "*" + domain;
        }
        if (local.length() == 2) {
            return local.charAt(0) + "*" + domain;
        }
        return local.charAt(0) + "***" + local.charAt(local.length() - 1) + domain;
    }

    private static String requirePrefix(String value) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException("registration.email.redis-key-prefix 不能为空");
        }
        return value.trim().replaceAll(":+$", "");
    }

    private static BusinessException expired() {
        return new BusinessException(410, "注册验证已过期或已耗尽，请重新验证");
    }

    private static BusinessException unavailable(Exception cause) {
        return new BusinessException(503, "邮箱验证服务暂不可用，请稍后重试", cause);
    }

    /** 只能由成功消费 Redis 邮箱会话的本服务构造。 */
    public static final class VerifiedRegistration {
        private final String username;
        private final String email;
        private final String inviteCode;

        private VerifiedRegistration(String username, String email, String inviteCode) {
            this.username = username;
            this.email = email;
            this.inviteCode = inviteCode;
        }

        public String username() {
            return username;
        }

        public String email() {
            return email;
        }

        public String inviteCode() {
            return inviteCode;
        }
    }
}
