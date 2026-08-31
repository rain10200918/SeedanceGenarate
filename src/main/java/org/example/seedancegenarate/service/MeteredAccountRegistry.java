package org.example.seedancegenarate.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.seedancegenarate.entity.AppUser;
import org.example.seedancegenarate.mapper.AppUserMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 「哪些账号买了席位、各买了多少」的<b>唯一名单</b>，带 30 秒缓存。
 *
 * <h3>为什么要有这个类</h3>
 * 两个地方要这份名单：对账任务（每 2 秒一轮）和 API 限流拦截器（每个请求）。
 * 各查各的话会漂成两套口径 —— 而漂了的症状是「对账在维护这个账号的登记表，
 * 限流却当它是个人用户」，两边都不报错。
 *
 * <h3>为什么要缓存</h3>
 * {@code WHERE account_tier IS NOT NULL OR concurrency_override IS NOT NULL} 在
 * {@code app_user} 上<b>没有索引可用，是全表扫</b>（该表除主键外无索引，OR 条件本身也难走索引）。
 * 对账每 2 秒一轮 = 每天 43200 次全表扫，而在没有企业客户之前它每次都返回空 —— 纯烧钱。
 *
 * <h3>缓存过期的两个方向都查过</h3>
 * <b>刚给某账号加席位</b>：新提交立刻受并发限制（{@code VideoSubmitServiceImpl.admit}
 * 每次实时读 app_user，不走这里）；只有「补登记在途任务」和「放宽它的接口限速」
 * 会晚最多 30 秒。管理端改席位时会主动调 {@link #invalidate()}，
 * 所以单实例是立即的，多实例最多 30 秒。<br>
 * <b>刚撤销某账号的席位</b>：最多 30 秒内它还享受着放宽后的接口限速，
 * 而并发限制已经实时不生效了 —— 这 30 秒里它能突发 {@code capacity} 次提交。
 * 钱包仍然逐条拦着，且只有管理员能触发这个状态，接受。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MeteredAccountRegistry {

    /** 改席位是人点按钮的低频动作，不需要 2 秒一查 */
    public static final long CACHE_MS = 30_000L;

    private final AppUserMapper appUserMapper;
    private final ConcurrencyPolicy concurrencyPolicy;

    private volatile Map<Long, Integer> cache = Map.of();
    private volatile long cachedAt;

    /** userId → 席位数。只含真正解析出上限的账号（档位拼错的会被 policy 判成不限，不在这里） */
    public Map<Long, Integer> seats() {
        long now = System.currentTimeMillis();
        if (cachedAt > 0 && now - cachedAt < CACHE_MS) {
            return cache;
        }
        Map<Long, Integer> fresh = query();
        cache = fresh;
        cachedAt = now;
        return fresh;
    }

    /** 买了席位的账号 id */
    public Set<Long> accountIds() {
        return seats().keySet();
    }

    /** 这个账号的席位数；<b>null = 没买额度</b>（个人用户走这条） */
    public Integer seatsOf(Long userId) {
        return userId == null ? null : seats().get(userId);
    }

    /**
     * 丢弃缓存。管理端改完席位立刻调，让这一实例上的接口限速当场跟上。
     * <p>
     * 多实例下只影响本机，其余实例最多 30 秒后自然过期 —— 不为这点差异引入广播，
     * 那会把一个纯读缓存变成一个需要保证送达的分布式问题。
     */
    public void invalidate() {
        cachedAt = 0;
    }

    private Map<Long, Integer> query() {
        Map<Long, Integer> result = new LinkedHashMap<>();
        for (AppUser user : appUserMapper.selectList(Wrappers.<AppUser>lambdaQuery()
                .select(AppUser::getId, AppUser::getAccountTier, AppUser::getConcurrencyOverride)
                .and(w -> w.isNotNull(AppUser::getAccountTier)
                        .or().isNotNull(AppUser::getConcurrencyOverride)))) {
            Integer max = concurrencyPolicy.accountMax(user);
            if (max != null) {
                result.put(user.getId(), max);
            }
        }
        return result;
    }
}
