package org.example.seedancegenarate.service.Impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.extern.slf4j.Slf4j;
import org.example.seedancegenarate.entity.Announcement;
import org.example.seedancegenarate.mapper.AnnouncementMapper;
import org.example.seedancegenarate.service.AnnouncementService;
import org.example.seedancegenarate.service.ConfigInvalidationNotifier;
import org.example.seedancegenarate.service.ConfigSnapshotReloadable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.List;

/**
 * 公告实现。
 * <p>
 * 用户端读走进程快照（{@code ConfigSnapshotReloadable}）：发布/下线/编辑后
 * 刷新本实例并广播失效，多实例秒级一致；广播丢失由 60s 兜底重载接管
 * （与模型开放开关同一模式，零新基础设施）。
 * <p>
 * 写操作全部 requireAdmin 入口；状态迁移：DRAFT → PUBLISHED → OFFLINE。
 */
@Slf4j
@Service
public class AnnouncementServiceImpl implements AnnouncementService, ConfigSnapshotReloadable {

    private final AnnouncementMapper mapper;
    private final ConfigInvalidationNotifier invalidationNotifier;

    /** 进程内快照：已发布公告（最新在前）。不可变读，reload 时整体替换。 */
    private volatile List<Announcement> publishedSnapshot = Collections.emptyList();

    public AnnouncementServiceImpl(AnnouncementMapper mapper, ConfigInvalidationNotifier notifier) {
        this.mapper = mapper;
        this.invalidationNotifier = notifier;
        reload();
    }

    @Override
    public List<Announcement> published() {
        return publishedSnapshot;
    }

    @Override
    public String snapshotType() {
        return ConfigInvalidationNotifier.TYPE_ANNOUNCEMENT;
    }

    /** 兜底重载：正常由失效广播即时触发，这里只在广播丢失或未启用广播时接管。 */
    @Scheduled(fixedDelayString = "${cache.config.reload-interval-ms:60000}")
    @Override
    public void reload() {
        try {
            List<Announcement> latest = mapper.selectList(Wrappers.<Announcement>lambdaQuery()
                    .eq(Announcement::getStatus, Announcement.STATUS_PUBLISHED)
                    .orderByDesc(Announcement::getCreateTime)
                    .orderByDesc(Announcement::getId));
            publishedSnapshot = latest;
        } catch (Exception e) {
            // 保留上一份快照：重载失败不能让用户读到空公告
            log.warn("重载公告快照失败（保留上一份）: {}", e.getMessage());
        }
    }

    @Override
    public Page<Announcement> page(long current, long size, String status) {
        return mapper.selectPage(new Page<>(Math.max(current, 1), Math.min(Math.max(size, 1), 100)),
                Wrappers.<Announcement>lambdaQuery()
                        .eq(StringUtils.hasText(status), Announcement::getStatus, status)
                        .orderByDesc(Announcement::getId));
    }

    @Override
    public Announcement create(String title, String content, Long adminId) {
        Announcement announcement = new Announcement();
        announcement.setTitle(title);
        announcement.setContent(sanitize(content));
        announcement.setStatus(Announcement.STATUS_DRAFT);
        announcement.setCreateBy(adminId);
        mapper.insert(announcement);
        return announcement;
    }

    @Override
    public void update(Long id, String title, String content) {
        Announcement existing = require(id);
        Announcement update = new Announcement();
        update.setId(id);
        if (StringUtils.hasText(title)) {
            update.setTitle(title);
        }
        // 正文仅草稿可改：已发布的正文改动应通过「下线 → 改 → 重新发布」走全量可见性
        if (StringUtils.hasText(content) && !Announcement.STATUS_PUBLISHED.equals(existing.getStatus())) {
            update.setContent(sanitize(content));
        }
        mapper.updateById(update);
        if (Announcement.STATUS_PUBLISHED.equals(existing.getStatus())) {
            refreshAndNotify();
        }
    }

    @Override
    public void publish(Long id) {
        Announcement existing = require(id);
        if (Announcement.STATUS_PUBLISHED.equals(existing.getStatus())) {
            return;
        }
        mapper.update(null, Wrappers.<Announcement>lambdaUpdate()
                .eq(Announcement::getId, id)
                .set(Announcement::getStatus, Announcement.STATUS_PUBLISHED));
        refreshAndNotify();
    }

    @Override
    public void unpublish(Long id) {
        Announcement existing = require(id);
        if (!Announcement.STATUS_PUBLISHED.equals(existing.getStatus())) {
            return;
        }
        mapper.update(null, Wrappers.<Announcement>lambdaUpdate()
                .eq(Announcement::getId, id)
                .set(Announcement::getStatus, Announcement.STATUS_OFFLINE));
        refreshAndNotify();
    }

    @Override
    public void remove(Long id) {
        require(id);
        mapper.deleteById(id);
        refreshAndNotify();
    }

    private Announcement require(Long id) {
        Announcement announcement = mapper.selectById(id);
        if (announcement == null) {
            throw new RuntimeException("公告不存在");
        }
        return announcement;
    }

    /** 本实例立即刷新 + 广播失效（多实例秒级一致） */
    private void refreshAndNotify() {
        reload();
        invalidationNotifier.notifyChanged(ConfigInvalidationNotifier.TYPE_ANNOUNCEMENT);
    }

    /**
     * 富文本 XSS 过滤：公告内容会以 v-html 渲染到所有用户页面，
     * 虽然发布者只有管理员，仍去掉 script/iframe/事件属性，双保险。
     */
    private String sanitize(String html) {
        if (!StringUtils.hasText(html)) {
            return html;
        }
        String cleaned = html
                .replaceAll("(?is)<script[^>]*>.*?</script>", "")
                .replaceAll("(?i)<iframe[^>]*>.*?</iframe>", "")
                .replaceAll("(?i)\\son[a-z]+\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>]+)", "");
        return cleaned;
    }
}
