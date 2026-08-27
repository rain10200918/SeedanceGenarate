package org.example.seedancegenarate.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.example.seedancegenarate.entity.VideoTask;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 产物过期判定。对齐 OSS 侧的生命周期规则「outputs/ 前缀 30 天后删除」。
 * <p>
 * 判错方向不对称，这是本文件几乎所有用例的立足点：
 * 判成「未过期」最多是用户点开拿到 410；判成「已过期」是<b>把还能看的东西藏起来</b>。
 * 所以一切拿不准的输入都必须落到「未过期」。
 */
class ArtifactExpiryPolicyTest {

    private static final int RETENTION = 30;

    private final ArtifactExpiryPolicy policy = new ArtifactExpiryPolicy(RETENTION);

    private VideoTask ossTask(int ageDays) {
        VideoTask task = new VideoTask();
        task.setBizTaskId("tsk_x");
        task.setStatus("SUCCESS");
        task.setArtifactStorageType("OSS");
        task.setArtifactKey("outputs/tsk_x/result.mp4");
        task.setCreateTime(LocalDateTime.now().minusDays(ageDays));
        return task;
    }

    @Test
    void artifactOlderThanRetentionIsExpired() {
        // 【测什么】超过保留期的 OSS 成功产物判为已过期
        // 【怎么算红】永远返回 false —— 前端拿不到任何信号，用户点开是一个转不出来的
        //            播放器（签名地址签得出来，但 OSS 上对象已被删，浏览器那边才 404）
        assertTrue(policy.isExpired(ossTask(31)));
        assertTrue(policy.isExpired(ossTask(365)));
    }

    @Test
    void artifactWithinRetentionIsNotExpired() {
        // 【测什么】保留期内的产物照常可播，行为一字未变
        // 【怎么算红】判定写反或边界写成 <= —— 昨天刚生成的视频被标成「已过期」，
        //            这是比不做还糟的结果：能看的东西被藏起来了
        assertFalse(policy.isExpired(ossTask(0)));
        assertFalse(policy.isExpired(ossTask(29)));
    }

    @Test
    void legacyLocalArtifactNeverExpires() {
        // 【测什么】非 OSS 的老本地产物永不判过期
        // 【怎么算红】只看时间不看存储类型 —— OSS 的生命周期规则根本管不到 data/videos/ 下的
        //            文件（那是另一台机器的磁盘，由 VideoCleanupTask 的 48h 规则管、且默认关闭），
        //            把它们标成过期是纯粹的误报
        VideoTask legacy = ossTask(999);
        legacy.setArtifactStorageType("LOCAL");
        assertFalse(policy.isExpired(legacy));

        legacy.setArtifactStorageType(null);
        assertFalse(policy.isExpired(legacy));
    }

    @Test
    void onlySuccessfulTasksCanExpire() {
        // 【测什么】没成功的任务不参与过期判定
        // 【怎么算红】不看 status —— 一个 30 天前提交、至今卡在 PROCESSING 的任务会被
        //            标成「视频已过期」，而它根本没有产物；用户看到的是彻底错误的解释
        VideoTask processing = ossTask(60);
        processing.setStatus("PROCESSING");
        assertFalse(policy.isExpired(processing));

        VideoTask failed = ossTask(60);
        failed.setStatus("FAILED");
        assertFalse(policy.isExpired(failed));
    }

    @Test
    void nullCreateTimeIsTreatedAsNotExpired() {
        // 【测什么】历史脏数据（create_time 为空）不判过期，也不炸
        // 【怎么算红】直接 createTime.isBefore(...) —— NPE 从判定里冒出来，
        //            而调用点是列表接口：一条脏数据让整个任务列表 500
        VideoTask noTime = ossTask(60);
        noTime.setCreateTime(null);
        assertFalse(policy.isExpired(noTime));
    }

    @Test
    void retentionBelowOneDisablesTheFeatureInsteadOfExpiringEverything() {
        // 【测什么】保留期配 0 / 负数 = 关闭该功能
        // 【怎么算红】按字面算 now.minusDays(0) —— **全站所有产物瞬间显示「已过期」**，
        //            看起来像整个系统挂了，而且是一次配置手滑就能触发
        assertFalse(new ArtifactExpiryPolicy(0).isExpired(ossTask(999)));
        assertFalse(new ArtifactExpiryPolicy(-1).isExpired(ossTask(999)));
    }

    @Test
    void stampMarksEveryRecordInAPage() {
        // 【测什么】分页出口逐条打标，过期与未过期都要有明确的 true/false
        // 【怎么算红】只打过期的、未过期的留 null —— 前端要区分「未过期」和「后端没算」
        //            就只能猜，而列表是用户最先看到的地方
        Page<VideoTask> page = new Page<>(1, 10);
        page.setRecords(List.of(ossTask(60), ossTask(1)));

        policy.stampAll(page);

        assertEquals(true, page.getRecords().get(0).getArtifactExpired());
        assertEquals(false, page.getRecords().get(1).getArtifactExpired());
    }

    @Test
    void nullInputsDoNotBlowUp() {
        // 【测什么】null 任务 / null 分页不抛异常
        // 【怎么算红】不判 null —— 打标是接口返回前的最后一步，在这里 NPE 会把
        //            一个本来成功的响应变成 500
        assertFalse(policy.isExpired(null));
        policy.stamp(null);
        policy.stampAll((Page<VideoTask>) null);
        policy.stampAll((List<VideoTask>) null);
    }

    @Test
    void messageCarriesTheRealRetentionSoItCannotLie() {
        // 【测什么】给用户的文案带真实天数，改配置文案跟着变
        // 【怎么算红】把「30 天」写死进文案 —— 哪天 OSS 规则改成 7 天，
        //            后端仍然告诉用户「保留 30 天」，用户按这句话规划就会丢东西
        assertTrue(new ArtifactExpiryPolicy(7).expiredMessage().contains("7 天"),
                "实际=" + new ArtifactExpiryPolicy(7).expiredMessage());
        assertTrue(policy.expiredMessage().contains("30 天"));
    }
}
