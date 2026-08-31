package org.example.seedancegenarate.service;

/**
 * 此刻这次提交要过的并发上限，<b>两层</b>。
 *
 * <h3>两层分别管什么</h3>
 * <b>账号总量</b>（{@code accountMax}）= 这个客户买到的量。名下所有 key、网页、画布的任务
 * 全都记在这一份里 —— 所以个人用户建 50 把 key 也不会多出任何容量。<br>
 * <b>key 份额</b>（{@code keyMax}）= 企业把总量在内部怎么切。生产 38 / 测试 2 / 市场部 8，
 * 互相不挤 —— 实习生的脚本跑飞只烧掉他自己那份。
 *
 * <h3>为什么 key 份额只能收紧</h3>
 * 它是「总量的一部分」，不是「额外的量」。允许放宽的话，企业自己建 key 就能自助扩容 ——
 * 和 D-030 里「按 key 分桶等于自助绕过限流」是同一个洞。
 *
 * @param accountMax 账号总量；<b>null = 不限</b>，0 = 禁止提交
 * @param keyMax     这把 key 的份额；<b>null = 没分配，与其他 key 共用账号总量</b>
 */
public record ConcurrencyLimit(Integer accountMax, Integer keyMax) {

    public static final ConcurrencyLimit UNLIMITED = new ConcurrencyLimit(null, null);

    public static ConcurrencyLimit of(int accountMax) {
        return new ConcurrencyLimit(Math.max(accountMax, 0), null);
    }

    public static ConcurrencyLimit of(Integer accountMax, Integer keyMax) {
        return new ConcurrencyLimit(
                accountMax == null ? null : Math.max(accountMax, 0),
                keyMax == null ? null : Math.max(keyMax, 0));
    }

    /** 两层都没有 → 调用方应当<b>整个跳过 Redis</b>，一次调用都不发 */
    public boolean unlimited() {
        return accountMax == null && keyMax == null;
    }

    /** 账号总量；不限时返回 0（配合 {@link #unlimited()} 用，别单独读） */
    public int max() {
        return accountMax == null ? 0 : accountMax;
    }

    /** 传给 Lua 的账号容量：不限时给一个永远撞不到的数，这样账号桶照常维护（对账要用） */
    public int accountCapForScript() {
        return accountMax == null ? Integer.MAX_VALUE : accountMax;
    }

    /** 传给 Lua 的 key 容量：<b>-1 = 这把 key 没分配份额，整个 key 桶跳过</b> */
    public int keyCapForScript() {
        return keyMax == null ? -1 : keyMax;
    }
}
