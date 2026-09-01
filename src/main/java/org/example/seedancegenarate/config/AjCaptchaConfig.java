package org.example.seedancegenarate.config;

import com.anji.captcha.service.CaptchaCacheService;
import com.anji.captcha.service.CaptchaService;
import com.anji.captcha.service.impl.CaptchaServiceFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Properties;

/** 显式装配 AJ-Captcha，避免其旧 starter 自动配置与本项目 Boot 3 生命周期耦合。 */
@Configuration
public class AjCaptchaConfig {

    @Bean
    public CaptchaCacheService ajCaptchaRedisCache(
            StringRedisTemplate redisTemplate,
            @Value("${captcha.redis-key-prefix:local:seedance:captcha}") String keyPrefix
    ) {
        RedisCaptchaCache cache = new RedisCaptchaCache(redisTemplate, keyPrefix);
        CaptchaServiceFactory.cacheService.put(cache.type(), cache);
        if (CaptchaServiceFactory.getCache("redis") != cache) {
            throw new IllegalStateException("AJ-Captcha Redis 缓存注册失败");
        }
        return cache;
    }

    @Bean
    public CaptchaService ajCaptchaService(CaptchaCacheService ajCaptchaRedisCache) {
        if (CaptchaServiceFactory.getCache("redis") != ajCaptchaRedisCache) {
            throw new IllegalStateException("AJ-Captcha 未使用应用共享 Redis");
        }
        Properties properties = new Properties();
        properties.setProperty("captcha.type", "blockPuzzle");
        properties.setProperty("captcha.cacheType", "redis");
        properties.setProperty("captcha.aes.status", "false");
        properties.setProperty("captcha.interference.options", "1");
        properties.setProperty("captcha.water.mark", "Ascent Creator");
        properties.setProperty("captcha.req.frequency.limit.enable", "0");
        return CaptchaServiceFactory.getInstance(properties);
    }

    static final class RedisCaptchaCache implements CaptchaCacheService {
        private final StringRedisTemplate redisTemplate;
        private final String keyPrefix;

        private RedisCaptchaCache(StringRedisTemplate redisTemplate, String keyPrefix) {
            this.redisTemplate = redisTemplate;
            String configured = keyPrefix == null ? "" : keyPrefix.trim();
            if (configured.isEmpty()) {
                throw new IllegalStateException("captcha.redis-key-prefix 不能为空");
            }
            this.keyPrefix = configured.replaceAll(":+$", "");
        }

        @Override
        public void set(String key, String value, long expiresInSeconds) {
            redisTemplate.opsForValue().set(key(key), value, Duration.ofSeconds(expiresInSeconds));
        }

        @Override
        public boolean exists(String key) {
            return Boolean.TRUE.equals(redisTemplate.hasKey(key(key)));
        }

        @Override
        public void delete(String key) {
            redisTemplate.delete(key(key));
        }

        @Override
        public String get(String key) {
            return redisTemplate.opsForValue().get(key(key));
        }

        @Override
        public String type() {
            return "redis";
        }

        private String key(String ajKey) {
            return keyPrefix + ":aj:" + ajKey;
        }
    }
}
