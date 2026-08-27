package org.example.seedancegenarate.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import lombok.extern.slf4j.Slf4j;
import org.example.seedancegenarate.entity.VideoTask;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 产物是否已被 OSS 生命周期规则删除。
 * <p>
 * <b>为什么是推导而不是定时清理</b>：OSS 侧配了「{@code outputs/} 前缀 30 天后删除」的
 * 生命周期规则。对应到应用侧，本来可以写个定时任务把过期任务的 {@code video_url} 置空，
 * 但那会<b>不可逆地抹掉数据</b>——之后想把保留期改成 60 天，已抹掉的救不回来；
 * 而且定时任务筛错行的代价是把没过期的也抹了。推导只是一次时间比较，
 * 改保留期就是改一个数，且一个字节都不动。
 * <p>
 * <b>为什么基准是 createTime 而不是 updateTime</b>：OSS 按对象的最后修改时间删，
 * 也就是 finalize 那一刻写进 OSS 的时间。{@code updateTime} 在 finalize 时与它相等，
 * 但<b>之后任何一次行更新都会把它往后推</b>——那会让应用说「没过期」而 OSS 已经删了，
 * 用户拿到一个转不出来的播放器。{@code createTime} 比 OSS 早，早的量等于这次生成耗时
 * （几分钟），方向是安全的：应用先说「已过期」，文件其实还在几分钟。
 * 30 天里差几分钟，换「永远不会出现无法解释的播放失败」。
 * <p>
 * <b>这个数必须和 OSS 控制台的规则对齐</b>。代码不去读 OSS 的规则（为此加一个跨网依赖
 * 不值得），改成靠 {@code StartupConfigLogger} 把它打进启动指纹，人可以一眼对照。
 */
@Slf4j
@Component
public class ArtifactExpiryPolicy {

    /** 与 OSS 生命周期规则 outputs-expire 对齐；< 1 表示不启用过期判定 */
    @Value("${video.artifact-retention-days:30}")
    private int retentionDays;

    /** 供测试构造；生产由 Spring 注入 */
    public ArtifactExpiryPolicy() {
    }

    public ArtifactExpiryPolicy(int retentionDays) {
        this.retentionDays = retentionDays;
    }

    /**
     * 该任务的产物是否已被 OSS 删除。
     * <p>
     * 四道前置条件缺一不可，任何一条不满足都判「未过期」——
     * 判错成「未过期」最多是用户点开拿到 410，判错成「已过期」是把还能看的东西藏起来。
     */
    public boolean isExpired(VideoTask task) {
        if (task == null || retentionDays < 1) {
            return false; // 配 0 / 负数 = 关闭该功能，不是「一切立刻过期」
        }
        // legacy 本地产物不归 OSS 规则管（它们由 VideoCleanupTask 的 48h 管，且默认关闭）
        if (!"OSS".equals(task.getArtifactStorageType())) {
            return false;
        }
        if (!"SUCCESS".equals(task.getStatus())) {
            return false; // 没成功就没有产物可谈
        }
        LocalDateTime createTime = task.getCreateTime();
        if (createTime == null) {
            return false; // 历史脏数据：宁可显示能播再 410，也不能把好数据说成过期
        }
        return createTime.isBefore(LocalDateTime.now().minusDays(retentionDays));
    }

    /** 打上派生标记后原样返回；null 安全 */
    public VideoTask stamp(VideoTask task) {
        if (task != null) {
            task.setArtifactExpired(isExpired(task));
        }
        return task;
    }

    /** 分页结果逐条打标；返回同一个 page 对象便于链式使用 */
    public <P extends IPage<VideoTask>> P stampAll(P page) {
        if (page != null) {
            stampAll(page.getRecords());
        }
        return page;
    }

    public List<VideoTask> stampAll(List<VideoTask> tasks) {
        if (tasks != null) {
            tasks.forEach(this::stamp);
        }
        return tasks;
    }

    /** 给用户看的文案，带上真实保留天数（改了配置文案跟着变，不会说谎） */
    public String expiredMessage() {
        return "视频已过期（仅保留 " + retentionDays + " 天）";
    }

    public int getRetentionDays() {
        return retentionDays;
    }
}
