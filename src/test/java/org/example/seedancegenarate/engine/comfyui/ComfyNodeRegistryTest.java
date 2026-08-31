package org.example.seedancegenarate.engine.comfyui;

import org.example.seedancegenarate.entity.ComfyNode;
import org.example.seedancegenarate.mapper.ComfyNodeMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 节点清单进库之后的三条硬约束：
 * <ol>
 *   <li>yaml 只是 <b>seed</b>：只 INSERT、永不 UPDATE</li>
 *   <li>查库失败时<b>保留上一次成功的清单</b>——pick() 在提交路径上，MySQL 抖一下不能让全站提交不了</li>
 *   <li>归档的节点<b>仍然留在清单里</b>，只是不探不派</li>
 * </ol>
 */
class ComfyNodeRegistryTest {

    private ComfyNodeMapper mapper;
    private ComfyUiProperties props;
    private ComfyNodeRegistry registry;

    @BeforeEach
    void setUp() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
                ComfyNode.class);

        mapper = mock(ComfyNodeMapper.class);
        props = new ComfyUiProperties();
        props.setNodes(List.of(yamlNode("gpu-0", "http://old/gpu-0"), yamlNode("gpu-1", "http://old/gpu-1")));
        registry = new ComfyNodeRegistry(mapper, props);
    }

    private static ComfyUiProperties.Node yamlNode(String id, String url) {
        ComfyUiProperties.Node n = new ComfyUiProperties.Node();
        n.setId(id);
        n.setBaseUrl(url);
        n.setEnabled(true);
        return n;
    }

    private static ComfyNode row(String id, String url, boolean enabled, boolean archived) {
        ComfyNode r = new ComfyNode();
        r.setId(id);
        r.setBaseUrl(url);
        r.setEnabled(enabled);
        r.setArchived(archived);
        r.setWeight(BigDecimal.ONE);
        return r;
    }

    @SuppressWarnings("unchecked")
    private void givenTable(List<ComfyNode> rows) {
        when(mapper.selectList(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class)))
                .thenReturn(new ArrayList<>(rows));
    }

    @Test
    void seedOnlyInsertsNodesThatAreNotInTheTableYet() {
        // 【测什么】seed 时已经在库里的节点<b>一个字都不改</b>
        // 【怎么算红】写成 upsert / insertOrUpdate —— 管理端把 gpu-0 的地址改到新机器上，
        //          下次重启就被 yaml 里的老地址静默覆盖回去，而且不报错。
        //          人会以为"改了没保存"，再改一次，再被覆盖
        when(mapper.selectById("gpu-0")).thenReturn(row("gpu-0", "http://new/gpu-0", true, false));
        when(mapper.selectById("gpu-1")).thenReturn(null);

        registry.seedFromYaml();

        verify(mapper, never()).updateById(any(ComfyNode.class));
        verify(mapper, times(1)).insert(any(ComfyNode.class)); // 只插了 gpu-1
    }

    @Test
    void seedKeepsTheEnabledFlagFromYaml() {
        // 【测什么】seed 进来的节点沿用 yaml 里的 enabled，不是一律 false
        // 【怎么算红】照搬「新增默认关闭」那条规则 —— 现有 6 台全是 enabled=true，
        //          升级到这个版本的那一刻全部变成关闭，全站派不出活。
        //          「新增默认关闭」是给**人在管理端新加**的机器定的，不是给存量迁移定的
        when(mapper.selectById(anyString())).thenReturn(null);

        registry.seedFromYaml();

        org.mockito.ArgumentCaptor<ComfyNode> captor =
                org.mockito.ArgumentCaptor.forClass(ComfyNode.class);
        verify(mapper, times(2)).insert(captor.capture());
        assertTrue(captor.getAllValues().stream().allMatch(ComfyNode::getEnabled),
                "yaml 里是开着的，seed 之后也该是开着的");
    }

    @Test
    void aDatabaseHiccupKeepsTheLastGoodListInsteadOfEmptyingIt() {
        // 【测什么】查库炸了的时候沿用上一次成功的清单
        // 【怎么算红】异常时返回空表 —— pick() 拿到零个候选，
        //          直接抛「没有可用的 ComfyUI 节点」。也就是说 **MySQL 抖一下 = 全站提交不了**，
        //          而这一层本来只是个配置读取，根本不该有这种杀伤力
        givenTable(List.of(row("gpu-0", "http://db/gpu-0", true, false)));
        assertEquals(1, registry.nodes().size(), "先成功一次，建立缓存");

        // 走真实路径：管理端刚改完节点（invalidate），紧接着这一次读撞上库故障。
        // 用反射直接改 cachedAt 就测不出 invalidate 与「从没成功过」共用哨兵那个洞了
        registry.invalidate();
        when(mapper.selectList(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class)))
                .thenThrow(new RuntimeException("Communications link failure"));

        List<ComfyUiProperties.Node> nodes = registry.nodes();

        assertEquals(1, nodes.size(), "该沿用上一次的清单");
        assertEquals("http://db/gpu-0", nodes.get(0).getBaseUrl(), "沿用的必须是库里那份，不是 yaml");
    }

    @Test
    void aDatabaseFailureBeforeAnySuccessFallsBackToYaml() {
        // 【测什么】进程刚起来就连不上库时回落 yaml，而不是零个节点
        // 【怎么算红】只有「保留上一次」没有「回落 yaml」—— 数据库先于后端启动完成
        //          是很常见的时序，那一刻缓存是空的，结果是后端起来了但一台节点都没有
        when(mapper.selectList(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class)))
                .thenThrow(new RuntimeException("Connection refused"));

        List<ComfyUiProperties.Node> nodes = registry.nodes();

        assertEquals(2, nodes.size(), "没有任何缓存时应回落 yaml");
    }

    @Test
    void archivedNodesStayInTheListSoTheirRunningTasksCanStillFindThem() {
        // 【测什么】归档的节点仍然出现在清单里（进而进快照、进 fleet.findNode）
        // 【怎么算红】query() 里加 `.eq(archived, false)` —— 归档一台机器的瞬间，
        //          它上面所有还没跑完的任务在 ComfyUiEngine.poll() 里查不到节点，
        //          直接 RemoteStatus.failed("找不到处理该任务的 ComfyUI 节点")。
        //          那是终态：任务判死、退款、GPU 上的 prompt 继续跑成孤儿。
        //          归档是「列表里别显示」，不是「从系统里消失」——过滤该发生在展示层
        givenTable(List.of(
                row("gpu-0", "http://db/gpu-0", true, false),
                row("gpu-old", "http://db/gpu-old", false, true)));

        List<ComfyUiProperties.Node> nodes = registry.nodes();

        assertEquals(2, nodes.size(), "归档的也要在清单里，实际=" + nodes);
        assertTrue(nodes.stream().anyMatch(n -> "gpu-old".equals(n.getId()) && n.isArchived()));
    }

    @Test
    void theListIsCachedAndInvalidateMakesTheNextReadHitTheDatabase() {
        // 【测什么】30 秒缓存生效；管理端改完调 invalidate() 后下一次读立刻看到新值
        // 【怎么算红】(a) 不缓存 —— 探测器每 3 秒 × N 个实例查一次全表；
        //          (b) invalidate 不生效 —— 管理端点了「保存」没反应，
        //              人会以为坏了然后去重启后端，而它其实只是要等 30 秒
        givenTable(List.of(row("gpu-0", "http://db/gpu-0", true, false)));
        registry.nodes();
        registry.nodes();
        registry.nodes();
        verify(mapper, times(1)).selectList(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));

        registry.invalidate();
        registry.nodes();

        verify(mapper, times(2)).selectList(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
    }
}
