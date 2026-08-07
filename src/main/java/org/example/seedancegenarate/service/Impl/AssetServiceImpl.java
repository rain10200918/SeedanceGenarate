package org.example.seedancegenarate.service.Impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.example.seedancegenarate.entity.AssetFolder;
import org.example.seedancegenarate.entity.UserAsset;
import org.example.seedancegenarate.mapper.AssetFolderMapper;
import org.example.seedancegenarate.mapper.UserAssetMapper;
import org.example.seedancegenarate.service.AssetService;
import org.example.seedancegenarate.service.OssService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 素材库实现：
 * - 登记幂等（同用户同 URL 只登记一次，靠 uk_asset_user_url 兜底）
 * - 查询走游标分页（keyset，WHERE id < cursor ORDER BY id DESC），不随页深退化
 * - 删除一律软删（素材被历史任务 video_task.images 引用，物理删会让详情/缩略图 404）
 */
@Service
@RequiredArgsConstructor
public class AssetServiceImpl implements AssetService {

    private final UserAssetMapper userAssetMapper;
    private final AssetFolderMapper assetFolderMapper;
    private final OssService ossService;

    @Override
    @Transactional
    public void registerAssets(Long userId, String taskId, List<String> urls) {
        List<String> distinct = urls.stream()
                .filter(StringUtils::hasText)
                .distinct()
                .collect(Collectors.toList());
        if (distinct.isEmpty()) return;
        Set<String> existUrls = userAssetMapper.selectList(
                        new LambdaQueryWrapper<UserAsset>()
                                .select(UserAsset::getUrl)
                                .eq(UserAsset::getUserId, userId)
                                .eq(UserAsset::getStatus, "ACTIVE")
                                .in(UserAsset::getUrl, distinct))
                .stream()
                .map(UserAsset::getUrl)
                .collect(Collectors.toSet());
        for (String url : distinct) {
            if (existUrls.contains(url)) continue;
            UserAsset asset = new UserAsset();
            asset.setUserId(userId);
            asset.setType("IMAGE");
            asset.setSource("TASK");
            asset.setUrl(url);
            asset.setTaskId(taskId);
            asset.setStatus("ACTIVE");
            userAssetMapper.insert(asset);
        }
    }

    @Override
    public UserAsset uploadImage(Long userId, Long folderId, MultipartFile file) throws Exception {
        if (file == null || file.isEmpty()) {
            throw new RuntimeException("请选择要上传的图片");
        }
        if (folderId != null) {
            requireOwnedFolder(userId, folderId);
        }
        String url = ossService.upload(file);
        UserAsset asset = new UserAsset();
        asset.setUserId(userId);
        asset.setType("IMAGE");
        asset.setSource("UPLOAD");
        asset.setUrl(url);
        asset.setFolderId(folderId);
        asset.setStatus("ACTIVE");
        userAssetMapper.insert(asset);
        return asset;
    }

    @Override
    public Map<String, Object> pageAssets(Long userId, Long folderId, Long cursorId, int size) {
        LambdaQueryWrapper<UserAsset> qw = new LambdaQueryWrapper<UserAsset>()
                .eq(UserAsset::getUserId, userId)
                .eq(UserAsset::getStatus, "ACTIVE")
                .orderByDesc(UserAsset::getId);
        if (folderId != null) {
            if (folderId == 0) {
                qw.isNull(UserAsset::getFolderId); // 0 = 未归档视图
            } else {
                qw.eq(UserAsset::getFolderId, folderId);
            }
        }
        if (cursorId != null) {
            qw.lt(UserAsset::getId, cursorId);
        }
        List<UserAsset> items = userAssetMapper.selectList(qw.last("LIMIT " + (size + 1)));
        boolean hasMore = items.size() > size;
        if (hasMore) {
            items = new ArrayList<>(items.subList(0, size));
        }
        Map<String, Object> result = new HashMap<>();
        result.put("items", items);
        result.put("nextCursor", hasMore ? items.get(items.size() - 1).getId() : null);
        return result;
    }

    @Override
    public List<AssetFolder> listFolders(Long userId) {
        return assetFolderMapper.selectList(
                new LambdaQueryWrapper<AssetFolder>()
                        .eq(AssetFolder::getUserId, userId)
                        .orderByAsc(AssetFolder::getParentId)
                        .orderByAsc(AssetFolder::getId));
    }

    @Override
    @Transactional
    public AssetFolder createFolder(Long userId, String name, Long parentId) {
        if (!StringUtils.hasText(name)) {
            throw new RuntimeException("文件夹名不能为空");
        }
        String trimmed = name.trim();
        if (parentId != null) {
            AssetFolder parent = assetFolderMapper.selectById(parentId);
            if (parent == null || !Objects.equals(parent.getUserId(), userId)) {
                throw new RuntimeException("父文件夹不存在");
            }
        }
        // 注意：eq(column, null) 会生成 parent_id = null（永假），根目录必须用 isNull 比较
        LambdaQueryWrapper<AssetFolder> dup = new LambdaQueryWrapper<AssetFolder>()
                .eq(AssetFolder::getUserId, userId)
                .eq(AssetFolder::getName, trimmed);
        if (parentId != null) {
            dup.eq(AssetFolder::getParentId, parentId);
        } else {
            dup.isNull(AssetFolder::getParentId);
        }
        if (assetFolderMapper.selectCount(dup) > 0) {
            throw new RuntimeException("同级已存在同名文件夹");
        }
        AssetFolder folder = new AssetFolder();
        folder.setUserId(userId);
        folder.setName(trimmed);
        folder.setParentId(parentId);
        assetFolderMapper.insert(folder);
        return folder;
    }

    @Override
    @Transactional
    public void renameFolder(Long userId, Long folderId, String name) {
        if (!StringUtils.hasText(name)) {
            throw new RuntimeException("文件夹名不能为空");
        }
        AssetFolder folder = requireOwnedFolder(userId, folderId);
        String trimmed = name.trim();
        LambdaQueryWrapper<AssetFolder> dup = new LambdaQueryWrapper<AssetFolder>()
                .eq(AssetFolder::getUserId, userId)
                .eq(AssetFolder::getName, trimmed)
                .ne(AssetFolder::getId, folderId);
        if (folder.getParentId() != null) {
            dup.eq(AssetFolder::getParentId, folder.getParentId());
        } else {
            dup.isNull(AssetFolder::getParentId);
        }
        if (assetFolderMapper.selectCount(dup) > 0) {
            throw new RuntimeException("同级已存在同名文件夹");
        }
        folder.setName(trimmed);
        assetFolderMapper.updateById(folder);
    }

    @Override
    @Transactional
    public void deleteFolder(Long userId, Long folderId) {
        AssetFolder folder = requireOwnedFolder(userId, folderId);
        // 素材移回未归档（不删素材）
        userAssetMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<UserAsset>()
                .eq(UserAsset::getUserId, userId)
                .eq(UserAsset::getFolderId, folderId)
                .set(UserAsset::getFolderId, null));
        // 级联删除子文件夹（先收集整棵子树再删）
        List<AssetFolder> all = assetFolderMapper.selectList(
                new LambdaQueryWrapper<AssetFolder>().eq(AssetFolder::getUserId, userId));
        Set<Long> toDelete = new HashSet<>();
        collectSubtree(all, folderId, toDelete);
        toDelete.add(folderId);
        assetFolderMapper.deleteByIds(toDelete);
    }

    private void collectSubtree(List<AssetFolder> all, Long parentId, Set<Long> toDelete) {
        for (AssetFolder f : all) {
            if (Objects.equals(f.getParentId(), parentId) && toDelete.add(f.getId())) {
                collectSubtree(all, f.getId(), toDelete);
            }
        }
    }

    @Override
    @Transactional
    public void moveAssets(Long userId, List<Long> assetIds, Long folderId) {
        if (assetIds == null || assetIds.isEmpty()) return;
        if (folderId != null) {
            requireOwnedFolder(userId, folderId);
        }
        for (Long assetId : assetIds) {
            UserAsset asset = requireOwnedAsset(userId, assetId);
            asset.setFolderId(folderId);
            userAssetMapper.updateById(asset);
        }
    }

    @Override
    public List<UserAsset> listByIds(Long userId, List<Long> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        return userAssetMapper.selectList(new LambdaQueryWrapper<UserAsset>()
                .eq(UserAsset::getUserId, userId)
                .eq(UserAsset::getStatus, "ACTIVE")
                .in(UserAsset::getId, ids));
    }

    @Override
    @Transactional
    public List<Long> softDeleteAssets(Long userId, List<Long> assetIds) {
        List<Long> refused = new ArrayList<>();
        if (assetIds == null) return refused;
        for (Long assetId : assetIds) {
            UserAsset asset = requireOwnedAsset(userId, assetId);
            // 二期：流水线节点引用检查在这里填充（assetUsages 返回的节点 >0 则拒绝）
            asset.setStatus("DELETED");
            userAssetMapper.updateById(asset);
        }
        return refused;
    }

    @Override
    public List<Long> assetUsages(Long userId, Long assetId) {
        // 二期流水线接入后：查 pipeline_node 中 asset_ids 包含该素材的节点 id
        return List.of();
    }

    private AssetFolder requireOwnedFolder(Long userId, Long folderId) {
        AssetFolder folder = assetFolderMapper.selectById(folderId);
        if (folder == null || !Objects.equals(folder.getUserId(), userId)) {
            throw new RuntimeException("文件夹不存在");
        }
        return folder;
    }

    private UserAsset requireOwnedAsset(Long userId, Long assetId) {
        UserAsset asset = userAssetMapper.selectById(assetId);
        if (asset == null || !Objects.equals(asset.getUserId(), userId)) {
            throw new RuntimeException("素材不存在");
        }
        return asset;
    }
}
