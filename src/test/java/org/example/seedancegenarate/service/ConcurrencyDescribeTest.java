package org.example.seedancegenarate.service;

import org.example.seedancegenarate.config.RateLimitConfig;
import org.example.seedancegenarate.config.ConcurrencyProperties;
import org.example.seedancegenarate.dto.ConcurrencyLimitView;
import org.example.seedancegenarate.entity.AppUser;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 管理端看到的「生效后长什么样」。
 * <p>
 * 这一层存在的唯一理由是<b>不让管理员被静默失效骗到</b>。
 * 设额度是一次商业行为 —— 页面显示「已设 50 路」而实际一路没限，
 * 会一直错到某天客户把队列打爆才被发现。
 */
class ConcurrencyDescribeTest {

    private ConcurrencyPolicy policy(ConcurrencyProperties p) {
        ConcurrencyPolicy policy = new ConcurrencyPolicy(p, new RateLimitConfig());
        ReflectionTestUtils.setField(policy, "taskTimeoutMinutes", 60);
        ReflectionTestUtils.setField(policy, "timeoutRetryMax", 2);
        return policy;
    }

    private ConcurrencyProperties props(Map<String, Integer> tiers) {
        ConcurrencyProperties p = new ConcurrencyProperties();
        p.setShadow(false);
        p.setTiers(new LinkedHashMap<>(tiers));
        return p;
    }

    private AppUser user(String tier, Integer override) {
        AppUser u = new AppUser();
        u.setId(7L);
        u.setAccountTier(tier);
        u.setConcurrencyOverride(override);
        return u;
    }

    @Test
    void unknownTierIsReportedAsUnlimitedWithAWarning() {
        // 【测什么】档位名不在配置里时，明确告诉管理员「当前按不限放行」并给出可用档位
        // 【怎么算红】只把请求里填的原样回显 —— 管理员敲了 ENTERPRICE（少个 S），页面显示
        //          ENTERPRICE、看起来设好了，客户实际一路没限。这个错要等到客户把队列
        //          打爆才会被发现，而那时没人会怀疑是当初拼错了一个字母
        ConcurrencyLimitView v = policy(props(Map.of("ENTERPRISE", 50)))
                .describe(user("ENTERPRICE", null));

        assertNull(v.effectiveLimit(), "生效值必须是「不限」，不能假装设上了");
        assertEquals("UNLIMITED", v.source());
        assertNotNull(v.warning(), "必须告警");
        assertTrue(v.warning().contains("ENTERPRICE") && v.warning().contains("不限"),
                "告警要点名是哪个档位、以及现在实际是什么，实际=" + v.warning());
        assertTrue(v.availableTiers().contains("ENTERPRISE"),
                "要把可用档位给出来，前端才能做成下拉而不是手敲");
    }

    @Test
    void emptyTierTableTellsTheAdminToUseTheOverrideInstead() {
        // 【测什么】配置里一个档位都没有（= 现在的线上状态）时，告诉管理员改用「单客户上限」
        // 【怎么算红】只说「档位不存在」——管理员会以为是自己敲错了，反复试各种名字，
        //          而真相是这套档位表压根还没启用，他该填的是另一个字段
        ConcurrencyLimitView v = policy(props(Map.of())).describe(user("ENTERPRISE", null));

        assertNull(v.effectiveLimit());
        assertTrue(v.warning().contains("填数字"), "要指路，实际=" + v.warning());
    }

    @Test
    void overrideWinsAndIsReportedAsTheSource() {
        // 【测什么】override 生效时，来源标成 OVERRIDE，且没有告警
        // 【怎么算红】来源算错 —— 管理员分不清「这 50 是档位给的还是单独谈的」，
        //          将来批量调档位时会漏掉/误伤这家单独谈过的客户
        ConcurrencyLimitView v = policy(props(Map.of("ENTERPRISE", 50)))
                .describe(user("ENTERPRISE", 200));

        assertEquals(200, v.effectiveLimit());
        assertEquals("OVERRIDE", v.source());
        assertNull(v.warning());
    }

    @Test
    void zeroIsCalledOutAsABanNotAsUnlimited() {
        // 【测什么】填 0 时明确说「一条都提交不了」，并告诉他「不限」该怎么填
        // 【怎么算红】0 不给提示 —— 想设「不限」的人很自然会填 0，
        //          结果是把客户整个封禁，而页面上那个 0 看起来人畜无害
        ConcurrencyLimitView v = policy(props(Map.of())).describe(user(null, 0));

        assertEquals(0, v.effectiveLimit());
        assertTrue(v.warning().contains("提交不了"), "实际=" + v.warning());
    }

    @Test
    void shadowModeIsSurfacedSoNobodyThinksTheLimitIsLive() {
        // 【测什么】影子模式下设了额度，要告诉管理员「现在还不会真拒」
        // 【怎么算红】不提影子 —— 管理员设完 50 路、以为生效了，客户超了也不会被拒；
        //          反过来等真关影子那天，一批账号会同时开始被拒，没人预料得到
        ConcurrencyProperties p = props(Map.of());
        p.setShadow(true);

        ConcurrencyLimitView v = policy(p).describe(user(null, 50));

        assertEquals(50, v.effectiveLimit());
        assertTrue(v.warning().contains("试运行"), "实际=" + v.warning());
    }

    @Test
    void masterSwitchOffIsAlsoSurfaced() {
        // 【测什么】功能整体关闭时，页面上设什么都不生效，要说清楚
        // 【怎么算红】照常显示生效值 —— 排查「为什么没限住」时会绕很久，
        //          因为页面白纸黑字写着已经限了 50
        ConcurrencyProperties p = props(Map.of("ENTERPRISE", 50));
        p.setEnabled(false);

        ConcurrencyLimitView v = policy(p).describe(user("ENTERPRISE", null));

        assertNull(v.effectiveLimit());
        assertTrue(v.warning().contains("没有开启"), "实际=" + v.warning());
    }

    @Test
    void warningsAreWrittenForOperationsNotForEngineers() {
        // 【测什么】所有告警文案里不出现配置项名、英文枚举、内部术语
        // 【怎么算红】把 "concurrency.tiers=..." / "shadow=true" / "OVERRIDE" 这类写进文案 ——
        //          看这个弹窗的是销售运营，不是工程师。看不懂就会来问人，
        //          问到第三次之后他们会训练出「直接忽略黄字」的习惯，真出问题那次也一起忽略
        ConcurrencyProperties shadow = props(Map.of("ENTERPRISE", 50));
        shadow.setShadow(true);
        ConcurrencyProperties off = props(Map.of());
        off.setEnabled(false);
        ConcurrencyProperties noDerive = props(Map.of());
        noDerive.setDeriveApiRateLimit(false);

        List<String> texts = List.of(
                policy(props(Map.of("ENTERPRISE", 50))).describe(user("ENTERPRICE", null)).warning(),
                policy(props(Map.of())).describe(user("ENTERPRISE", null)).warning(),
                policy(props(Map.of())).describe(user(null, 0)).warning(),
                policy(shadow).describe(user(null, 50)).warning(),
                policy(off).describe(user(null, 50)).warning(),
                policy(noDerive).describe(user(null, 50)).warning());

        for (String text : texts) {
            assertNotNull(text);
            for (String jargon : List.of("concurrency.", "shadow", "OVERRIDE", "UNLIMITED",
                    "enabled=", "在途", "令牌", "ZSET", "null")) {
                assertTrue(!text.contains(jargon),
                        "文案里不该出现「" + jargon + "」，实际=" + text);
            }
        }
    }

    @Test
    void aCleanEnterpriseSetupHasNoWarningAtAll() {
        // 【测什么】正常配好时不要有任何告警（告警多了就没人看了）
        // 【怎么算红】无脑挂告警 —— 每次都弹一堆黄字，管理员会训练出「直接忽略」的习惯，
        //          真出问题那次也一起被忽略
        ConcurrencyLimitView v = policy(props(Map.of("ENTERPRISE", 50)))
                .describe(user("ENTERPRISE", null));

        assertEquals(50, v.effectiveLimit());
        assertEquals("TIER", v.source());
        assertNull(v.warning(), "正常情况不该有告警，实际=" + v.warning());
    }

    // ---------- 席位数 vs 接口提交速率 ----------

    @Test
    void theSeatNumberComesWithTheThroughputThatBacksIt() {
        // 【测什么】设了席位就一并回「接口每分钟几次 / 实际撑得住几个」，且撑得住的 >= 席位数
        // 【怎么算红】只回席位数 —— 席位是「能同时跑几个」，可客户得先提交得进来。
        //          两者不匹配时席位数就是个装饰品（设 50 实际只到 25），
        //          而这件事在页面上一个字都看不出来，只会变成一张对不上的账单
        ConcurrencyLimitView v = policy(props(Map.of())).describe(user(null, 50));

        assertEquals(50, v.effectiveLimit());
        assertEquals(10, v.apiRatePerMinute(), "50 席 ÷ 任务 5 分钟 = 每分钟要放 10 次");
        assertEquals(50, v.reachableInFlight(), "10 次/分钟 × 5 分钟 = 稳态 50 个在途");
        assertNull(v.warning(), "推导开着的时候两者天然匹配，不该有告警");
    }

    @Test
    void turningOffTheDerivationTurnsTheMismatchIntoAVisibleWarning() {
        // 【测什么】把自动放宽关掉后，席位数超过默认桶能撑住的量时立刻出黄字
        // 【怎么算红】关掉就不吭声 —— 那么「关掉推导」是一个静默把所有企业客户降到 25 席的动作。
        //          关掉可以，但不许悄悄地关：这个开关的整个安全性就建立在「关了看得见」上
        ConcurrencyProperties p = props(Map.of());
        p.setDeriveApiRateLimit(false);

        ConcurrencyLimitView v = policy(p).describe(user(null, 50));

        assertEquals(50, v.effectiveLimit());
        assertEquals(25, v.reachableInFlight(), "默认桶 5 次/分钟 × 5 分钟 = 只撑得住 25");
        assertNotNull(v.warning(), "必须告警");
        assertTrue(v.warning().contains("25") && v.warning().contains("50"),
                "要把「设了多少」和「实际只能到多少」两个数都摆出来，实际=" + v.warning());
    }

    @Test
    void unlimitedAccountsGetNoThroughputNumbersAtAll() {
        // 【测什么】不限额的账号（全体个人用户）这两个字段是 null
        // 【怎么算红】给他们也算一个数 —— 前端会把「接口能撑住 25 个」显示在一个
        //          压根没有席位概念的账号上，运营会以为这个客户被限到了 25
        ConcurrencyLimitView v = policy(props(Map.of())).describe(user(null, null));

        assertNull(v.apiRatePerMinute());
        assertNull(v.reachableInFlight());
    }
}
