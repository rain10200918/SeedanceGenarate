package org.example.seedancegenarate.interceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.example.seedancegenarate.config.RateLimitConfig;
import org.example.seedancegenarate.entity.ApiKey;
import org.example.seedancegenarate.exception.ApiErrorResponse;
import org.example.seedancegenarate.exception.ApiException;
import org.example.seedancegenarate.exception.ApiExceptionHandler;
import org.example.seedancegenarate.service.ConcurrencyPolicy;
import org.example.seedancegenarate.service.MeteredAccountRegistry;
import org.example.seedancegenarate.service.RateLimitResult;
import org.example.seedancegenarate.service.TokenBucketRateLimitService;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;

/**
 * 对外 API 按钥匙限流（令牌桶，仅限提交类请求）。429 带 Retry-After 头，
 * 错误按统一契约输出。须在 ApiKeyInterceptor 之后执行（依赖注入的 api_key 属性）。
 *
 * <h3>两档桶：买了席位的账号用推导出来的松桶</h3>
 * 并发额度上线之后，令牌桶对这两类账号的职责已经不同了：
 * <ul>
 *   <li><b>没席位的</b>（全体个人用户）—— 令牌桶是唯一防线，参数一个字不改</li>
 *   <li><b>有席位的</b> —— 并发额度才是防线，而且它自带时钟（跑完一个才进一个）。
 *       令牌桶此时只需要挡住病态捶打（重试风暴），再按 5 次/分钟卡着，
 *       就是在跟自己卖出去的席位数打架：默认桶撑得住的在途数只有 25，
 *       50 席以上的数字全是装饰品</li>
 * </ul>
 *
 * <h3>判别条件只能是「管理员给没给席位」</h3>
 * 用的是 {@link MeteredAccountRegistry#seatsOf}，它背后是
 * {@code ConcurrencyPolicy.accountMax()} —— 只读档位和 override 这两个<b>管理员专属</b>字段。
 * <b>绝不能</b>换成 {@code resolve(...).unlimited()}：那个还含
 * {@code api_key.max_concurrency}，是用户自助能改的，用它当判别条件等于让任何人
 * 给自己的 key 设个份额就跳进松桶。
 */
@Component
@RequiredArgsConstructor
public class ApiKeyRateLimitInterceptor implements HandlerInterceptor {
    private final TokenBucketRateLimitService tokenBucketRateLimitService;
    private final RateLimitConfig rateLimitConfig;
    private final MeteredAccountRegistry meteredAccountRegistry;
    private final ConcurrencyPolicy concurrencyPolicy;
    private final ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod()) || !"POST".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        Object attribute = request.getAttribute("api_key");
        if (!(attribute instanceof ApiKey apiKey)) {
            return true; // 鉴权失败由 ApiKeyInterceptor 处理
        }
        // 分桶键是**账号**不是 key id。配额卖给账号，key 只是凭证——
        // 一个账号发几把 key 是它自己的组织方式，不该改变它买到的量。
        // 按 key 分桶时，自助创建 key 就等于自助绕过限流（建 N 把 = N 倍配额）。
        RateLimitResult result = tokenBucketRateLimitService.tryAcquire(
                "api-key-owner:" + apiKey.getUserId(), bucketFor(apiKey.getUserId()));
        if (result.allowed()) {
            return true;
        }
        response.setStatus(429);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json;charset=UTF-8");
        response.setHeader("Retry-After", String.valueOf(result.retryAfterSeconds()));
        response.getWriter().write(objectMapper.writeValueAsString(
                new ApiErrorResponse(new ApiErrorResponse.ApiError(
                        ApiException.rateLimited().getCode(), "请求过于频繁，请稍后再试",
                        ApiExceptionHandler.requestId(request)))));
        return false;
    }

    /**
     * 没席位 → 默认桶（个人用户的行为逐字不变）；有席位 → 按席位推导的松桶。
     * <p>
     * 名单来自 30 秒缓存，这里<b>不查库</b> —— 限流器不能依赖它保护的那个资源，
     * 否则重试风暴（正是最需要它的时刻）会把 MySQL 一起拖下水。
     */
    private RateLimitConfig.Bucket bucketFor(Long userId) {
        Integer seats = meteredAccountRegistry.seatsOf(userId);
        if (seats == null) {
            return rateLimitConfig.getApiKey();
        }
        return concurrencyPolicy.apiBucketFor(seats);
    }
}
