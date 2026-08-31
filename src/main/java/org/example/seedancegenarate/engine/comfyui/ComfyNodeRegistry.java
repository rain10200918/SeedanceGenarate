package org.example.seedancegenarate.engine.comfyui;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.seedancegenarate.entity.ComfyNode;
import org.example.seedancegenarate.mapper.ComfyNodeMapper;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 节点清单的<b>唯一来源</b>，带 30 秒缓存。加一台机器不再需要改 yaml + 重启。
 *
 * <h3>yaml 从此只是 seed，不是配置</h3>
 * 启动时把 {@code video.comfyui.nodes} 里的节点<b>只 INSERT、不 UPDATE</b> 进表。
 * 之后 yaml 的 {@code nodes:} 就不再生效 —— 改它没用，要改就在管理端改。
 * <p>
 * 为什么 seed 走代码不走迁移 SQL：yaml 里是 {@code ${COMFYUI_NODE0_URL:...}}，
 * <b>环境变量可覆盖</b>。写死进迁移的话，生产用了别的 env 就会灌进错地址。
 * <p>
 * 为什么只 INSERT 不 UPDATE：管理端改过的地址，重启不该被 yaml 覆盖回去。
 * upsert 写法会让人的每一次修改在下次重启时静默失效，而且不报错。
 *
 * <h3>降级链：表 → 上一次成功的清单 → yaml</h3>
 * {@code pick()} 在提交路径上，<b>MySQL 抖一下不能让全站选不出节点</b>。
 * 查库失败时保留上一次成功的结果（内存里那份）；只有「进程刚起来就连不上库」
 * 这个组合才回落 yaml。稳态下 yaml 完全不参与。
 * <p>
 * 这条降级是无条件的，<b>不加开关</b> —— 少一个配置项，也少一个「出事时才发现开关配错了」的可能。
 *
 * <h3>归档的节点仍然留在清单里</h3>
 * 只有探测器和管理端列表会跳过它们。清单本身必须包含归档节点，
 * 否则它上面的在途任务会在 {@code ComfyUiEngine.poll()} 里查不到节点、被直接判 FAILED。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ComfyNodeRegistry {

    /** 加节点是人点按钮的低频动作，不需要 3 秒一查。改完管理端会主动 invalidate */
    public static final long CACHE_MS = 30_000L;

    private final ComfyNodeMapper comfyNodeMapper;
    private final ComfyUiProperties properties;

    private volatile List<ComfyUiProperties.Node> cache = List.of();
    private volatile long cachedAt;
    /**
     * 有没有成功查到过。<b>必须和 {@link #cachedAt} 分开</b>。
     * <p>
     * 合用一个哨兵（{@code cachedAt == 0} 同时表示"从没查过"和"缓存已作废"）会开一个洞：
     * 管理端改完节点调了 {@link #invalidate()}，紧接着这一次读正好撞上 MySQL 抖动 ——
     * 降级逻辑会以为"从没成功过"，于是<b>回落 yaml</b>，把库里的清单（含刚加的机器、
     * 人改过的地址）整个换成一份可能很旧的静态配置。而这恰恰发生在有人正在动机队的时刻。
     */
    private volatile boolean everLoaded;

    @PostConstruct
    void seedFromYaml() {
        for (ComfyUiProperties.Node node : properties.getNodes()) {
            try {
                if (comfyNodeMapper.selectById(node.getId()) != null) {
                    continue; // 已经在库里 —— 绝不覆盖，人可能改过
                }
                ComfyNode row = new ComfyNode();
                row.setId(node.getId());
                row.setBaseUrl(node.getBaseUrl());
                // seed 保留 yaml 里的 enabled：现有 6 台都是 true，
                // 一律置 false 会让这次升级把全站派活能力关掉
                row.setEnabled(node.isEnabled());
                row.setArchived(false);
                row.setWeight(BigDecimal.valueOf(node.getWeight()));
                row.setRemark("从 application.yaml 首次导入");
                comfyNodeMapper.insert(row);
                log.info("ComfyUI 节点首次入库: {} -> {}", node.getId(), node.getBaseUrl());
            } catch (Exception e) {
                // 迁移还没跑 / 库不通 —— 不能拦着应用起来，降级链会回落 yaml
                log.warn("ComfyUI 节点 seed 失败（将回落 yaml）: {} - {}", node.getId(), e.getMessage());
            }
        }
        log.info("ComfyUI 节点清单来自数据库；application.yaml 的 nodes 只在首次 seed 时使用，改它不生效");
    }

    /** 全部节点（<b>含归档</b>）。调度、探测、findNode 都走这里 */
    public List<ComfyUiProperties.Node> nodes() {
        long now = System.currentTimeMillis();
        if (everLoaded && cachedAt > 0 && now - cachedAt < CACHE_MS) {
            return cache;
        }
        try {
            List<ComfyUiProperties.Node> fresh = query();
            cache = fresh;
            cachedAt = now;
            everLoaded = true;
            return fresh;
        } catch (Exception e) {
            if (everLoaded) {
                // 保留上一次成功的清单。清空的话 = MySQL 抖一下全站提交不了
                log.warn("ComfyUI 节点清单查询失败，沿用上一次的 {} 台: {}", cache.size(), e.getMessage());
                return cache;
            }
            log.error("ComfyUI 节点清单查询失败且无缓存，回落 application.yaml: {}", e.getMessage());
            return properties.getNodes();
        }
    }

    /**
     * 管理端改完节点立刻调，让这一实例当场跟上。多实例最多 30 秒后自然过期。
     * <p>
     * <b>只作废新鲜度，不动 {@code everLoaded}</b> —— 否则「改完节点」紧接着
     * 「库抖了一下」就会退回 yaml，而不是沿用刚才那份。
     */
    public void invalidate() {
        cachedAt = 0;
    }

    private List<ComfyUiProperties.Node> query() {
        List<ComfyUiProperties.Node> result = new ArrayList<>();
        for (ComfyNode row : comfyNodeMapper.selectList(
                Wrappers.<ComfyNode>lambdaQuery().orderByAsc(ComfyNode::getId))) {
            ComfyUiProperties.Node node = new ComfyUiProperties.Node();
            node.setId(row.getId());
            node.setBaseUrl(row.getBaseUrl());
            node.setEnabled(Boolean.TRUE.equals(row.getEnabled()));
            node.setArchived(Boolean.TRUE.equals(row.getArchived()));
            node.setWeight(row.getWeight() == null ? 1.0 : row.getWeight().doubleValue());
            node.setRemark(row.getRemark());
            result.add(node);
        }
        return result;
    }
}
