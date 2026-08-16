package org.example.seedancegenarate.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.example.seedancegenarate.entity.Announcement;

/**
 * 公告：用户端读（进程快照，发布秒级全网可见）+ 管理端写（CRUD + 发布/下线）。
 */
public interface AnnouncementService {

    /** 用户端：已发布公告（最新在前），走进程快照缓存 */
    java.util.List<Announcement> published();

    /** 管理端：分页（全部状态，最新在前） */
    Page<Announcement> page(long current, long size, String status);

    /** 管理端：新建（草稿） */
    Announcement create(String title, String content, Long adminId);

    /** 管理端：更新标题/正文（仅草稿可改正文；已发布可改标题） */
    void update(Long id, String title, String content);

    /** 管理端：发布（草稿 → 已发布）或重新发布（下线 → 已发布） */
    void publish(Long id);

    /** 管理端：下线（已发布 → 下线） */
    void unpublish(Long id);

    /** 管理端：删除（任何状态） */
    void remove(Long id);
}
