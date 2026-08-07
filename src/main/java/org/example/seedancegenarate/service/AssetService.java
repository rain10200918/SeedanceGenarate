package org.example.seedancegenarate.service;

import org.example.seedancegenarate.entity.AssetFolder;
import org.example.seedancegenarate.entity.UserAsset;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * 用户素材库：任务图片自动登记、文件夹归档、软删与游标分页查询。
 * 素材 URL 全部来自本系统 OSS（白名单保证），登记不转存、零拷贝。
 */
public interface AssetService {

    /** 批量登记素材（幂等：同用户同 URL 只登记一次）；供任务提交事件监听器调用 */
    void registerAssets(Long userId, String taskId, List<String> urls);

    /** 素材库独立上传：传 OSS + 登记（source=UPLOAD，taskId 为空）；folderId 为 null 归未归档 */
    UserAsset uploadImage(Long userId, Long folderId, MultipartFile file) throws Exception;

    /** 游标分页查询（id 倒序）；返回 {items, nextCursor}，nextCursor 为 null 表示没有更多 */
    Map<String, Object> pageAssets(Long userId, Long folderId, Long cursorId, int size);

    /** 全部文件夹（平铺返回，前端组树） */
    List<AssetFolder> listFolders(Long userId);

    /** 创建文件夹（同级同名防重，父文件夹须属于本人） */
    AssetFolder createFolder(Long userId, String name, Long parentId);

    /** 重命名（同级同名防重） */
    void renameFolder(Long userId, Long folderId, String name);

    /** 删除文件夹：连同子文件夹删除，内部素材移回未归档（不删素材） */
    void deleteFolder(Long userId, Long folderId);

    /** 批量移动素材到文件夹；folderId 为 null 表示移回未归档 */
    void moveAssets(Long userId, List<Long> assetIds, Long folderId);

    /** 批量软删素材；返回因被引用而拒绝的 id 列表（流水线接入后填充） */
    List<Long> softDeleteAssets(Long userId, List<Long> assetIds);

    /** 批量查询本人 ACTIVE 素材（流水线节点缩略图反查用） */
    List<UserAsset> listByIds(Long userId, List<Long> ids);

    /** 素材被哪些流水线节点引用（删除保护；二期流水线接入后填充） */
    List<Long> assetUsages(Long userId, Long assetId);
}
