package org.example.seedancegenarate.controller;

import lombok.RequiredArgsConstructor;
import org.example.seedancegenarate.context.UserContext;
import org.example.seedancegenarate.entity.AssetFolder;
import org.example.seedancegenarate.entity.Result;
import org.example.seedancegenarate.entity.UserAsset;
import org.example.seedancegenarate.service.AssetService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/** 用户素材库：任务图片自动沉淀，支持文件夹归档、移动、软删、游标分页查询 */
@RestController
@RequestMapping("/api/assets")
@RequiredArgsConstructor
public class AssetController {

    private final AssetService assetService;

    /** 游标分页（id 倒序）；cursor 传上一页返回的 nextCursor */
    @GetMapping
    public Result<Map<String, Object>> page(
            @RequestParam(required = false) Long folderId,
            @RequestParam(required = false) Long cursor,
            @RequestParam(defaultValue = "24") int size) {
        return Result.success(assetService.pageAssets(UserContext.requireUserId(), folderId, cursor, size));
    }

    /** 独立上传图片到素材库（folderId 为空归未归档） */
    @PostMapping("/upload")
    public Result<UserAsset> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) Long folderId) throws Exception {
        return Result.success(assetService.uploadImage(UserContext.requireUserId(), folderId, file));
    }

    /** 全部文件夹（平铺，前端组树） */
    @GetMapping("/folders")
    public Result<List<AssetFolder>> folders() {
        return Result.success(assetService.listFolders(UserContext.requireUserId()));
    }

    @PostMapping("/folders")
    public Result<AssetFolder> createFolder(@RequestBody CreateFolderRequest req) {
        return Result.success(assetService.createFolder(UserContext.requireUserId(), req.name(), req.parentId()));
    }

    @PutMapping("/folders/{id}")
    public Result<Void> renameFolder(@PathVariable Long id, @RequestBody RenameFolderRequest req) {
        assetService.renameFolder(UserContext.requireUserId(), id, req.name());
        return Result.success(null);
    }

    /** 删除文件夹：内部素材移回未归档，不删素材 */
    @DeleteMapping("/folders/{id}")
    public Result<Void> deleteFolder(@PathVariable Long id) {
        assetService.deleteFolder(UserContext.requireUserId(), id);
        return Result.success(null);
    }

    /** 批量移动素材；folderId 为 null 表示移回未归档 */
    @PutMapping("/move")
    public Result<Void> move(@RequestBody MoveRequest req) {
        assetService.moveAssets(UserContext.requireUserId(), req.assetIds(), req.folderId());
        return Result.success(null);
    }

    /** 批量软删素材；返回被引用而拒绝的 id 列表（流水线接入后可能非空） */
    @DeleteMapping
    public Result<List<Long>> delete(@RequestBody DeleteRequest req) {
        return Result.success(assetService.softDeleteAssets(UserContext.requireUserId(), req.assetIds()));
    }

    /** 按 id 批量查询本人素材（流水线节点缩略图反查） */
    @GetMapping("/batch")
    public Result<List<UserAsset>> batch(@RequestParam("ids") List<Long> ids) {
        return Result.success(assetService.listByIds(UserContext.requireUserId(), ids));
    }

    /** 素材被哪些流水线节点引用（删除保护；二期流水线接入后填充） */
    @GetMapping("/{id}/usages")
    public Result<List<Long>> usages(@PathVariable Long id) {
        return Result.success(assetService.assetUsages(UserContext.requireUserId(), id));
    }

    /** 新建文件夹 */
    public record CreateFolderRequest(String name, Long parentId) {
    }

    /** 重命名文件夹 */
    public record RenameFolderRequest(String name) {
    }

    /** 批量移动素材到文件夹（folderId=null 移回未归档） */
    public record MoveRequest(List<Long> assetIds, Long folderId) {
    }

    /** 批量软删素材 */
    public record DeleteRequest(List<Long> assetIds) {
    }
}
