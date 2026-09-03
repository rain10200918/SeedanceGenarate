package org.example.seedancegenarate.service.Impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.example.seedancegenarate.entity.UserAsset;
import org.example.seedancegenarate.mapper.AssetFolderMapper;
import org.example.seedancegenarate.mapper.UserAssetMapper;
import org.example.seedancegenarate.service.OssService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 素材库「同图幂等」：OSS 键是内容 md5，同一张图传两次地址一样，uk_asset_user_url 只许一条。
 * 2026-09-04 线上：用户第二次传同一张参考图，直接 1062 Duplicate entry 500 出去。
 */
class AssetServiceImplTest {

    private static final long USER = 3L;
    private static final String URL = "https://bucket.oss-cn-beijing.aliyuncs.com/images/abc.png";

    private final UserAssetMapper assets = mock(UserAssetMapper.class);
    private final AssetFolderMapper folders = mock(AssetFolderMapper.class);
    private final OssService oss = mock(OssService.class);
    private final MultipartFile file = new MockMultipartFile("file", "cat.png", "image/png", new byte[]{1, 2, 3});
    private AssetServiceImpl service;

    /** LambdaQueryWrapper.select(UserAsset::getUrl) 要查列名缓存，纯单测里没有 Spring 初始化，手动建 */
    @BeforeAll
    static void initTableInfo() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
                UserAsset.class);
    }

    @BeforeEach
    void setUp() throws Exception {
        when(oss.upload(any(MultipartFile.class))).thenReturn(URL);
        when(assets.insert(any(UserAsset.class))).thenReturn(1);
        when(assets.updateById(any(UserAsset.class))).thenReturn(1);
        service = new AssetServiceImpl(assets, folders, oss);
    }

    @Test
    void uploadingTheSameImageAgainReturnsTheExistingRowInsteadOfFailing() throws Exception {
        // 【测什么】同用户同地址已经有一条：不再 insert（会撞唯一键），把已有那条原样给回去
        // 【怎么算红】uploadImage 不先查直接 insert
        UserAsset existing = row(11L, "ACTIVE", 5L);
        when(assets.selectOne(any())).thenReturn(existing);

        UserAsset got = service.uploadImage(USER, null, file);

        assertSame(existing, got);
        assertEquals(5L, got.getFolderId(), "没指定文件夹就别把它从原来的文件夹里挪出来");
        verify(assets, never()).insert(any(UserAsset.class));
    }

    @Test
    void uploadingAPreviouslyDeletedImageRevivesIt() throws Exception {
        // 【测什么】软删过的行还占着唯一键：用户再传这张图必须复活它（status 回 ACTIVE、进指定文件夹），不能 500
        // 【怎么算红】复活时不改 status，或者遇到已有行直接抛
        UserAsset deleted = row(12L, "DELETED", null);
        when(assets.selectOne(any())).thenReturn(deleted);
        when(folders.selectById(7L)).thenReturn(folder(7L));

        UserAsset got = service.uploadImage(USER, 7L, file);

        assertEquals("ACTIVE", got.getStatus());
        assertEquals(7L, got.getFolderId());
        verify(assets).updateById(deleted);
        verify(assets, never()).insert(any(UserAsset.class));
    }

    @Test
    void losingTheRaceOnInsertFallsBackToTheWinnersRow() throws Exception {
        // 【测什么】两个请求同时传同一张图：先查没有、insert 撞唯一键 → 再查一次拿赢家那条，而不是把 1062 抛给用户
        // 【怎么算红】去掉 insert 外面的 DuplicateKeyException 兜底
        UserAsset winner = row(13L, "ACTIVE", null);
        when(assets.selectOne(any())).thenReturn(null, winner);
        when(assets.insert(any(UserAsset.class))).thenThrow(new DuplicateKeyException("1062"));

        UserAsset got = service.uploadImage(USER, null, file);

        assertSame(winner, got);
    }

    @Test
    void firstUploadInsertsAnUploadSourcedActiveRow() throws Exception {
        // 【测什么】第一次传：正常插一条 UPLOAD 来源、ACTIVE 的行
        when(assets.selectOne(any())).thenReturn(null);

        UserAsset got = service.uploadImage(USER, null, file);

        ArgumentCaptor<UserAsset> inserted = ArgumentCaptor.forClass(UserAsset.class);
        verify(assets).insert(inserted.capture());
        assertSame(inserted.getValue(), got);
        assertEquals(URL, got.getUrl());
        assertEquals("UPLOAD", got.getSource());
        assertEquals("ACTIVE", got.getStatus());
        verify(assets, never()).updateById(any(UserAsset.class));
    }

    @Test
    void registeringTaskImagesSkipsUrlsThatAlreadyExistEvenIfDeleted() {
        // 【测什么】任务登记的查重不能只看 ACTIVE：软删的行也占着唯一键，再插就是 1062；删过的图也不因为又拿去生成就复活
        // 【怎么算红】查重条件里加回 status = 'ACTIVE'（wrapper 的 SQL 片段会出现 status）
        UserAsset deleted = row(14L, "DELETED", null);
        when(assets.selectList(any())).thenReturn(List.of(deleted));

        service.registerAssets(USER, "tsk-1", List.of(URL, "https://bucket/images/new.png"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<UserAsset>> wrapper = ArgumentCaptor.forClass(Wrapper.class);
        verify(assets).selectList(wrapper.capture());
        String where = ((LambdaQueryWrapper<UserAsset>) wrapper.getValue()).getSqlSegment();
        assertFalse(where.contains("status"), "查重不许按 status 过滤: " + where);

        ArgumentCaptor<UserAsset> inserted = ArgumentCaptor.forClass(UserAsset.class);
        verify(assets).insert(inserted.capture());
        assertEquals("https://bucket/images/new.png", inserted.getValue().getUrl());
        verify(assets, never()).updateById(any(UserAsset.class));
        assertNull(deleted.getTaskId(), "软删的行不该被动");
    }

    @Test
    void registeringSwallowsADuplicateFromAConcurrentTask() {
        // 【测什么】两个任务同时带同一张图：insert 撞唯一键不往外抛（事务里其它 URL 照常登记）
        // 【怎么算红】去掉 registerAssets 里 insert 的 DuplicateKeyException 兜底
        when(assets.selectList(any())).thenReturn(List.of());
        when(assets.insert(any(UserAsset.class))).thenThrow(new DuplicateKeyException("1062")).thenReturn(1);

        service.registerAssets(USER, "tsk-2", List.of(URL, "https://bucket/images/second.png"));

        verify(assets, org.mockito.Mockito.times(2)).insert(any(UserAsset.class));
    }

    private static UserAsset row(long id, String status, Long folderId) {
        UserAsset a = new UserAsset();
        a.setId(id);
        a.setUserId(USER);
        a.setType("IMAGE");
        a.setSource("UPLOAD");
        a.setUrl(URL);
        a.setFolderId(folderId);
        a.setStatus(status);
        return a;
    }

    private static org.example.seedancegenarate.entity.AssetFolder folder(long id) {
        org.example.seedancegenarate.entity.AssetFolder f = new org.example.seedancegenarate.entity.AssetFolder();
        f.setId(id);
        f.setUserId(USER);
        f.setName("参考图");
        return f;
    }
}
