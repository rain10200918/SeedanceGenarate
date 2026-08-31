package org.example.seedancegenarate.service.Impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.example.seedancegenarate.entity.ApiKey;
import org.example.seedancegenarate.mapper.ApiKeyMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 自助端「删除」的可见性与配额语义。
 * <p>
 * 用户点删除后必须同时成立三件事：立刻失效、<b>从列表消失</b>、<b>不再占名额</b>。
 * 缺一件，用户就会觉得「删了但没删掉」——尤其第三件：第一版 {@code countByOwner}
 * 数了全部状态，用户删满 50 把之后<b>再也建不了新 key，而列表里一把都看不见</b>。
 * <p>
 * 但库里的行必须留着：{@code api_call_log.api_key_id} / {@code video_task.api_key_id}
 * 都是无外键约束的裸列，真删行不报错，只会让「这笔消费是哪把 key 花的」从此答不出来。
 * <p>
 * <b>本测试捕获 service 真正传给 mapper 的 Wrapper</b>，不是在测试里自己造一个来断言
 * ——后者改坏 service 也不会红，等于没测。
 */
class ApiKeyOwnerScopeTest {

    private static final Long OWNER = 7L;

    @BeforeAll
    static void initTableInfo() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
                ApiKey.class);
    }

    private ApiKeyMapper mapper;
    private ApiKeyServiceImpl service;

    @BeforeEach
    void setUp() {
        mapper = mock(ApiKeyMapper.class);
        service = new ApiKeyServiceImpl();
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
    }

    /** 把 Wrapper 渲染成「SQL 片段 + 参数值」，便于对条件做断言 */
    private static String render(Wrapper<ApiKey> wrapper) {
        String params = wrapper instanceof com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?> aw
                ? String.valueOf(aw.getParamNameValuePairs())
                : "";
        return wrapper.getSqlSegment() + " || params=" + params;
    }

    @Test
    @SuppressWarnings("unchecked")
    void ownerListFiltersByOwnerAndHidesDeletedOnes() {
        // 【测什么】自助列表真正下发的条件同时含「属主」和「ENABLED」
        // 【怎么算红】service 里漏掉 status 过滤 —— 用户删掉的 key 还挂在列表里，
        //          他以为没删成功就反复点；漏掉 userId 过滤则是横向越权，
        //          任意登录用户能看到全平台的 key 前缀、备注和回调地址
        when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        service.listByOwner(OWNER);

        ArgumentCaptor<Wrapper<ApiKey>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).selectList(captor.capture());
        String sql = render(captor.getValue());

        // MyBatis-Plus 的条件值是占位符（#{ew.paramNameValuePairs.MPGENVALn}），
        // 断言得落在**列名**上——而要挡的回归恰恰是「整个过滤条件被删掉」
        assertTrue(sql.contains("user_id"), "必须按属主过滤，实际=" + sql);
        assertTrue(sql.contains("status"), "必须按状态过滤（否则已删的还在列表里），实际=" + sql);
    }

    @Test
    @SuppressWarnings("unchecked")
    void deletedKeysDoNotOccupyTheQuota() {
        // 【测什么】名额计数真正下发的条件排除了已删的
        // 【怎么算红】数全部状态（我第一版就是这么写的）—— 用户删满 50 把之后
        //          「已达上限」但列表空空如也，他完全无法理解发生了什么，
        //          而且再也建不出新 key
        when(mapper.selectCount(any(Wrapper.class))).thenReturn(3L);

        service.countByOwner(OWNER);

        ArgumentCaptor<Wrapper<ApiKey>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).selectCount(captor.capture());
        String sql = render(captor.getValue());

        assertTrue(sql.contains("status"), "计数必须排除已删的，实际=" + sql);
        assertTrue(sql.contains("user_id"), "计数必须限定属主，实际=" + sql);
    }

    @Test
    @SuppressWarnings("unchecked")
    void deleteScopesTheUpdateByOwnerSoItCannotTouchOthers() {
        // 【测什么】删除下发的 UPDATE 里带着属主条件（而不是先查再判）
        // 【怎么算红】WHERE 里只有 id —— 任何人能停掉别人的生产 key，
        //          对方整条 API 接入立刻中断，而且这在「先查再判」的写法下
        //          还多一个 TOCTOU 窗口
        when(mapper.update(any(), any(Wrapper.class))).thenReturn(1);

        service.revokeOwned(42L, OWNER);

        ArgumentCaptor<Wrapper<ApiKey>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).update(any(), captor.capture());
        String sql = render(captor.getValue());

        assertTrue(sql.contains("user_id"), "UPDATE 必须带属主条件，实际=" + sql);
        assertTrue(sql.contains("DISABLED"), "要置为 DISABLED，实际=" + sql);
    }

    @Test
    @SuppressWarnings("unchecked")
    void clearingAKeyShareMustActuallyWriteNull() {
        // 【测什么】把份额清空时，下发的 UPDATE 里真的有 max_concurrency 这一列
        // 【怎么算红】用 updateById 或者「null 就跳过」—— MyBatis-Plus 的 updateById 会略过 null 字段，
        //          于是「取消分配」这个动作永远执行不了：用户点了、提示成功了、
        //          份额还在，这把 key 继续被一个他以为已经删掉的数字卡着
        when(mapper.update(any(), any(Wrapper.class))).thenReturn(1);

        service.setShareOwned(42L, OWNER, null);

        ArgumentCaptor<Wrapper<ApiKey>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).update(any(), captor.capture());
        Wrapper<ApiKey> wrapper = captor.getValue();
        String set = wrapper instanceof com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<?> lu
                ? lu.getSqlSet() : "";
        assertTrue(set != null && set.contains("max_concurrency"),
                "清空也必须显式 set 这一列，实际 SET=" + set);
        assertTrue(render(wrapper).contains("user_id"), "UPDATE 仍要带属主条件");
    }

    @Test
    @SuppressWarnings("unchecked")
    void shareUpdateIsScopedByOwner() {
        // 【测什么】改份额的 UPDATE 带属主条件
        // 【怎么算红】WHERE 里只有 id —— 任何登录用户都能把别人生产密钥的份额改成 0，
        //          对方整条接入立刻停摆，而且这属于静默破坏：对方那边只看到「一直提交不了」
        when(mapper.update(any(), any(Wrapper.class))).thenReturn(1);

        service.setShareOwned(42L, OWNER, 5);

        ArgumentCaptor<Wrapper<ApiKey>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).update(any(), captor.capture());
        assertTrue(render(captor.getValue()).contains("user_id"),
                "实际=" + render(captor.getValue()));
    }
}
