package org.example.seedancegenarate.service.llm;

import org.example.seedancegenarate.config.PromptOptimizeConfig;
import org.example.seedancegenarate.entity.LlmChannel;
import org.example.seedancegenarate.mapper.LlmChannelMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LLM 通道进库之后的硬约束，和 ComfyNodeRegistry 那套完全同形：
 * yaml 只是 seed（只 INSERT）；查库失败沿用上一次；归档行留在清单里；路由只看启用未归档。
 */
class LlmChannelRegistryTest {

    private LlmChannelMapper mapper;
    private PromptOptimizeConfig config;
    private LlmChannelRegistry registry;

    @BeforeEach
    void setUp() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
                LlmChannel.class);
        mapper = mock(LlmChannelMapper.class);
        config = new PromptOptimizeConfig();
        config.setUrl("http://yaml-host/v1/chat/completions");
        config.setApiKey("sk-yaml-key-0123456789");
        config.setModel("gemma-4-31b");
        registry = new LlmChannelRegistry(mapper, config);
    }

    private static LlmChannel row(String name, String model, boolean enabled, boolean archived, int priority) {
        LlmChannel r = new LlmChannel();
        r.setName(name);
        r.setBaseUrl("http://" + name + "/v1/chat/completions");
        r.setApiKey("sk-" + name + "-secret-0123456789");
        r.setModel(model);
        r.setTemperature(new BigDecimal("0.70"));
        r.setMaxTokens(1500);
        r.setTokenParam("max_tokens");
        r.setTimeoutMs(100_000);
        r.setPriority(priority);
        r.setEnabled(enabled);
        r.setArchived(archived);
        return r;
    }

    @SuppressWarnings("unchecked")
    private void givenTable(List<LlmChannel> rows) {
        when(mapper.selectList(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class)))
                .thenReturn(new ArrayList<>(rows));
    }

    @Test
    void seedInsertsTheYamlChannelOnlyWhenDefaultIsAbsent() {
        // 【测什么】表里没有 default 时，把 yaml 那份灌进去一条，且是启用的
        // 【怎么算红】seed 一律 enabled=false —— 升级那一刻「AI 润色」对所有用户不可用，
        //            而这条通道升级前明明在服务
        when(mapper.selectById(LlmChannelRegistry.SEED_NAME)).thenReturn(null);
        registry.seedFromYaml();
        verify(mapper, times(1)).insert(any(LlmChannel.class));
        var captor = org.mockito.ArgumentCaptor.forClass(LlmChannel.class);
        verify(mapper).insert(captor.capture());
        assertEquals("default", captor.getValue().getName());
        assertEquals("gemma-4-31b", captor.getValue().getModel());
        assertTrue(captor.getValue().getEnabled(), "seed 进来的那条必须是启用的");
    }

    @Test
    void seedNeverOverwritesAnExistingDefault() {
        // 【测什么】default 已经在库里时一个字都不改（管理端可能已把它指到别的服务/换了 key）
        // 【怎么算红】写成 upsert —— 管理员把 default 的 key 换掉，下次重启被 yaml 悄悄换回旧 key，且不报错
        when(mapper.selectById(LlmChannelRegistry.SEED_NAME)).thenReturn(row("default", "other-model", true, false, 100));
        registry.seedFromYaml();
        verify(mapper, never()).insert(any(LlmChannel.class));
        verify(mapper, never()).updateById(any(LlmChannel.class));
    }

    @Test
    void seedIsSkippedWhenYamlHasNoUsableConfig() {
        // 【测什么】yaml 三样缺一样就不 seed，也不抛（生产可能刻意只用管理端配）
        // 【怎么算红】不判空直接 insert —— 灌进一条 url/key 为空的通道，路由选到它必失败
        config.setApiKey("");
        registry.seedFromYaml();
        verify(mapper, never()).insert(any(LlmChannel.class));
    }

    @Test
    void aDatabaseHiccupKeepsTheLastGoodListInsteadOfEmptyingIt() {
        // 【测什么】查库炸了时沿用上一次成功的清单
        // 【怎么算红】异常时返回空表 —— 「AI 润色」在 MySQL 抖动的几秒里对所有用户报「未配置」
        givenTable(List.of(row("default", "gemma", true, false, 100), row("deepseek", "deepseek-v3", true, false, 200)));
        assertEquals(2, registry.channels().size());
        registry.invalidate();
        when(mapper.selectList(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class)))
                .thenThrow(new RuntimeException("connection reset"));
        List<LlmChannelSpec> afterHiccup = registry.channels();
        assertEquals(2, afterHiccup.size(), "必须沿用上一次的两条，而不是空表");
        assertEquals("default", afterHiccup.get(0).name());
    }

    @Test
    void aDatabaseFailureBeforeAnySuccessFallsBackToYaml() {
        // 【测什么】进程刚起来就连不上库时回落 yaml 那一条，而不是零条
        // 【怎么算红】只有「保留上一次」没有「回落 yaml」—— 数据库晚于后端就绪的那一分钟里全站没有通道
        when(mapper.selectList(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class)))
                .thenThrow(new RuntimeException("connection refused"));
        List<LlmChannelSpec> list = registry.channels();
        assertEquals(1, list.size());
        assertEquals("default", list.get(0).name());
        assertEquals("http://yaml-host/v1/chat/completions", list.get(0).baseUrl());
        assertTrue(list.get(0).routable());
    }

    @Test
    void invalidateDoesNotForgetThatWeOnceLoadedSuccessfully() {
        // 【测什么】invalidate 只作废新鲜度；紧接着库抖一下必须沿用上一次的清单，不是回落 yaml
        // 【怎么算红】invalidate 连 everLoaded 一起清 —— 管理端刚加完 deepseek，库抖一下，
        //            清单被整个换成 yaml 里那条老配置，恰好发生在有人正在动通道的时刻
        givenTable(List.of(row("default", "gemma", true, false, 100), row("deepseek", "deepseek-v3", true, false, 200)));
        assertEquals(2, registry.channels().size());
        registry.invalidate();
        when(mapper.selectList(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class)))
                .thenThrow(new RuntimeException("deadlock"));
        List<LlmChannelSpec> list = registry.channels();
        assertEquals(2, list.size(), "必须是刚才那两条，不是 yaml 的一条");
    }

    @Test
    void archivedAndDisabledChannelsStayInTheListButNotInRouting() {
        // 【测什么】channels() 含归档/停用（试跑要能指到它们、usage 要能回查）；routable() 只含启用未归档
        // 【怎么算红】(a) query 里过滤 archived —— 试跑指不到归档通道，历史 usage 里的通道名查无此人
        //            (b) routable 不看 archived —— 归档但 enabled 残留 true 的通道继续被路由选中
        givenTable(List.of(
                row("default", "gemma", true, false, 100),
                row("old-relay", "gpt-4o", true, true, 50),      // 归档但 enabled 残留 true
                row("trial", "deepseek-v3", false, false, 10)));  // 停用
        assertEquals(3, registry.channels().size());
        List<LlmChannelSpec> routable = registry.routable();
        assertEquals(1, routable.size());
        assertEquals("default", routable.get(0).name());
        assertNotNull(registry.find("old-relay"), "归档通道必须能按名字找到");
        assertFalse(registry.find("old-relay").routable());
    }

    @Test
    void routingOrderIsPriorityThenName() {
        // 【测什么】routable() 按 priority 升序，同 priority 按名字，结果确定
        // 【怎么算红】不排序 —— 路由顺序跟着 MySQL 返回顺序漂，同样的配置两次重启选到不同的主通道
        givenTable(List.of(
                row("zeta", "m", true, false, 100),
                row("alpha", "m", true, false, 100),
                row("primary", "m", true, false, 1)));
        List<String> order = registry.routable().stream().map(LlmChannelSpec::name).toList();
        assertEquals(List.of("primary", "alpha", "zeta"), order);
    }

    @Test
    void theListIsCachedAndInvalidateMakesTheNextReadHitTheDatabase() {
        // 【测什么】30 秒内重复读不查库；invalidate 后下一次读立刻查库
        // 【怎么算红】(a) 不缓存 —— 每次「AI 润色」查一次全表；(b) invalidate 不生效 —— 管理端点了没反应，
        //            人会以为坏了然后去重启后端
        givenTable(List.of(row("default", "gemma", true, false, 100)));
        registry.channels();
        registry.channels();
        registry.channels();
        verify(mapper, times(1)).selectList(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
        registry.invalidate();
        registry.channels();
        verify(mapper, times(2)).selectList(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
    }

    @Test
    void maskedKeyNeverLeaksMoreThanSixPlusTwoCharacters() {
        // 【测什么】脱敏形态：前 6 后 2；短 key 整串遮
        // 【怎么算红】按固定位置切但不判长度 —— 8 位以内的 key 被原样露出；或者遮得太少能撞回原 key
        assertEquals("sk-a7f••••••1b", LlmChannelSpec.maskKey("sk-a7f8e3c2-9d4b-4e5f-8a7b-6c5d4e3f2a1b"));
        assertEquals("••••••••", LlmChannelSpec.maskKey("sk-short"));
        assertEquals("", LlmChannelSpec.maskKey(null));
        assertFalse(LlmChannelSpec.maskKey("sk-a7f8e3c2-9d4b-4e5f-8a7b-6c5d4e3f2a1b").contains("e3c2"));
    }
}
