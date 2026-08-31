package org.example.seedancegenarate.controller;

import org.example.seedancegenarate.context.UserContext;
import org.example.seedancegenarate.engine.comfyui.ComfyNodeRegistry;
import org.example.seedancegenarate.entity.AppUser;
import org.example.seedancegenarate.entity.ComfyNode;
import org.example.seedancegenarate.mapper.ComfyNodeMapper;
import org.example.seedancegenarate.service.NodeHealthService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.bind.annotation.DeleteMapping;

import java.lang.reflect.Method;
import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 管理端加/改节点的三条硬约束：新增强制关闭 · 归档连带停用 · <b>没有删除</b>。
 */
class AdminComfyNodeAdminTest {

    private ComfyNodeMapper mapper;
    private ComfyNodeRegistry registry;
    private AdminComfyUiController controller;

    @BeforeEach
    void setUp() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
                ComfyNode.class);

        mapper = mock(ComfyNodeMapper.class);
        registry = mock(ComfyNodeRegistry.class);
        controller = new AdminComfyUiController(mock(NodeHealthService.class), mapper, registry);

        AppUser admin = new AppUser();
        admin.setId(1L);
        admin.setRole("admin");
        UserContext.setUser(admin);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private AdminComfyUiController.NodeUpsertRequest request() {
        return new AdminComfyUiController.NodeUpsertRequest();
    }

    @Test
    void newNodesAreAlwaysCreatedDisabled() {
        // 【测什么】新增节点一律 enabled=false，请求体里写 true 也不认
        // 【怎么算红】改成 row.setEnabled(request.getEnabled()) —— 一台刚填进来、
        //          插件还没装齐、模型还没同步的机器立刻开始接真实用户的活：
        //          提交过去报 missing_node_type，任务失败退款，用户重试再撞上。
        //          正确流程是加进来 → 用「指定节点提交」跑通 → 再开。
        //          这是安全默认，不该让调用方能覆盖
        when(mapper.selectById(anyString())).thenReturn(null);
        AdminComfyUiController.NodeUpsertRequest req = request();
        req.setId("gpu-new");
        req.setBaseUrl("http://new/gpu-new");
        req.setEnabled(true); // 调用方明确要求开启 —— 不认

        controller.create(req);

        ArgumentCaptor<ComfyNode> captor = ArgumentCaptor.forClass(ComfyNode.class);
        verify(mapper).insert(captor.capture());
        assertFalse(captor.getValue().getEnabled(), "新增的节点必须是关闭的");
    }

    @Test
    void creatingADuplicateIdIsRejectedInsteadOfOverwriting() {
        // 【测什么】id 已存在时报错，不是静默覆盖
        // 【怎么算红】直接 insert/upsert —— 把一台在跑活的机器的地址覆盖成新机器的，
        //          原机器上所有在途任务的 poll 会打到错误的地址上（history 查不到 → 判丢失 → 重投）
        ComfyNode existing = new ComfyNode();
        existing.setId("gpu-0");
        when(mapper.selectById("gpu-0")).thenReturn(existing);
        AdminComfyUiController.NodeUpsertRequest req = request();
        req.setId("gpu-0");
        req.setBaseUrl("http://somewhere/else");

        assertTrue(assertThrows(RuntimeException.class, () -> controller.create(req))
                .getMessage().contains("gpu-0"));
    }

    @Test
    void archivingANodeAlsoDisablesIt() {
        // 【测什么】归档必然连带 enabled=false
        // 【怎么算红】只写 archived 不动 enabled —— 得到一个「列表里看不见、却还在接活」的节点。
        //          这是最难发现的一种状态：运维以为它退役了，它还在跑活；
        //          出问题时人根本不会去看一台"已经不存在"的机器
        ComfyNode existing = new ComfyNode();
        existing.setId("gpu-old");
        when(mapper.selectById("gpu-old")).thenReturn(existing);
        AdminComfyUiController.NodeUpsertRequest req = request();
        req.setArchived(true);

        controller.update("gpu-old", req);

        // lambdaUpdate 的 SQL 片段里必须同时出现 archived 和 enabled 两个 set
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.Wrapper> captor =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.Wrapper.class);
        verify(mapper).update(any(), captor.capture());
        String sql = captor.getValue().getSqlSet();
        assertTrue(sql.contains("archived"), "该写 archived，实际 SET=" + sql);
        assertTrue(sql.contains("enabled"), "归档必须连带停用，实际 SET=" + sql);
    }

    @Test
    void changingANodeInvalidatesTheCacheSoTheChangeTakesEffectNow() {
        // 【测什么】改完节点主动作废缓存
        // 【怎么算红】不调 invalidate —— 管理端点了「保存」，30 秒内看不到任何变化。
        //          人会以为坏了，然后去重启后端（而它其实只是要等缓存过期）
        ComfyNode existing = new ComfyNode();
        existing.setId("gpu-0");
        when(mapper.selectById("gpu-0")).thenReturn(existing);
        AdminComfyUiController.NodeUpsertRequest req = request();
        req.setWeight(BigDecimal.valueOf(0.45));

        controller.update("gpu-0", req);

        verify(registry).invalidate();
    }

    @Test
    void thereIsNoDeleteEndpointAtAll() {
        // 【测什么】这个 controller 上没有任何 @DeleteMapping —— 结构性守卫
        // 【怎么算红】有人"顺手"加个删除按钮。删掉一台正在跑 3 个 minimax 的机器 =
        //          ComfyUiEngine.poll() 里 findNode 返回 null → RemoteStatus.failed(...)，
        //          那是**终态**：3 个任务当场判死 + 3 笔退款 + 约 60 分钟 H100 机时白烧，
        //          而 GPU 上那 3 个 prompt 还会继续跑到完（成了孤儿）。
        //          历史任务的 node_id 也一并悬空，事后归因从此查不了。
        //          归档拿到的是"列表里看不见"这个唯一真实需求，代价只是几百字节的行留着
        for (Method m : AdminComfyUiController.class.getDeclaredMethods()) {
            assertNull(m.getAnnotation(DeleteMapping.class),
                    "不许有删除接口，只能归档（方法 " + m.getName() + "）");
        }
    }

    @Test
    void invalidWeightIsRejectedBeforeItCanPoisonScheduling() {
        // 【测什么】0/负数权重在管理边界直接拒绝，不写进数据库
        // 【怎么算红】继续依赖调度侧 max(weight, 0.01) 兜底 —— 页面保存成功但实际权重被静默改写，
        //          管理员以为节点已停流，实际仍会拿到任务；配置错误也永远不会被暴露
        when(mapper.selectById(anyString())).thenReturn(null);
        AdminComfyUiController.NodeUpsertRequest req = request();
        req.setId("gpu-bad");
        req.setBaseUrl("http://gpu-bad:8188");
        req.setWeight(BigDecimal.ZERO);

        assertThrows(IllegalArgumentException.class, () -> controller.create(req));
        verify(mapper, org.mockito.Mockito.never()).insert(any(ComfyNode.class));
    }

    @Test
    void anEmptyPatchIsRejectedInsteadOfProducingInvalidSql() {
        // 【测什么】PATCH 空对象返回 400，不让 MyBatis 生成没有 SET 子句的 UPDATE
        // 【怎么算红】不跟踪是否真的设置了字段 —— 前端误发空 payload 时进入 mapper.update，
        //          最终以 SQL 语法异常变成 500，用户只看到“系统繁忙”
        ComfyNode existing = new ComfyNode();
        existing.setId("gpu-0");
        when(mapper.selectById("gpu-0")).thenReturn(existing);

        assertThrows(IllegalArgumentException.class, () -> controller.update("gpu-0", request()));
        verify(mapper, org.mockito.Mockito.never()).update(any(), any());
    }

    @Test
    void anArchivedNodeCannotBeReenabledWhileRemainingHidden() {
        // 【测什么】已归档节点不能只把 enabled 打开，必须先显式取消归档
        // 【怎么算红】允许 archived=true + enabled=true 共存 —— 节点默认列表里不可见，
        //          人以为它退役了；未来任何一处漏掉 archived 过滤就会静默重新接活
        ComfyNode existing = new ComfyNode();
        existing.setId("gpu-old");
        existing.setArchived(true);
        when(mapper.selectById("gpu-old")).thenReturn(existing);
        AdminComfyUiController.NodeUpsertRequest req = request();
        req.setEnabled(true);

        assertThrows(IllegalArgumentException.class, () -> controller.update("gpu-old", req));
        verify(mapper, org.mockito.Mockito.never()).update(any(), any());
    }
}
