package org.example.seedancegenarate.service;

import org.example.seedancegenarate.entity.ApiKey;

import java.util.List;

/**
 * 对外 API 钥匙管理：生成（明文只展示一次、哈希存储）、撤销、校验。
 * 存储当前为 MyBatis 表；将来 key 校验可加缓存，实现可换。
 */
public interface ApiKeyService {

    /** 创建结果：明文 key（只展示一次）+ 落库记录 */
    record CreatedApiKey(String plainKey, ApiKey record) {
    }

    /** 创建一把钥匙，明文只返回一次，库中仅存 SHA-256 哈希 */
    CreatedApiKey create(Long userId, String name, String callbackUrl);

    /** 撤销（禁用）：之后所有调用返回 API_KEY_DISABLED */
    void revoke(Long id);

    // —— 自助端（用户只能操作自己名下的 key）——
    //
    // 归属条件一律写进 SQL 的 WHERE，不做「先查、再判、再写」：
    // 后者既有 TOCTOU，又依赖每个调用方都记得判——而忘掉一次就是横向越权，
    // 那把 key 花的是别人的余额。写进 WHERE 则结构上想忘也忘不掉（同 D-010 的思路）。

    /** 自助创建：额外记录签发者与来源 IP */
    CreatedApiKey createOwned(Long userId, String name, String callbackUrl,
                              Long createdBy, String createdIp);

    /** 该账号名下的全部 key */
    List<ApiKey> listByOwner(Long userId);

    /** 该账号名下的 key 数量（数量上限用） */
    long countByOwner(Long userId);

    /** 改备注；不存在或不属于该账号返回 false（调用方转 404，不泄漏存在性） */
    boolean renameOwned(Long id, Long userId, String name);

    /** 撤销；不存在或不属于该账号返回 false */
    boolean revokeOwned(Long id, Long userId);

    /** 分配份额：归属写进 WHERE，不属于该账号时更新 0 行。null = 清空（共用账号总量） */
    boolean setShareOwned(Long id, Long userId, Integer maxConcurrency);

    /** 该账号已分配出去的份额之和（只算在用的 key） */
    int allocatedShare(Long userId);

    /** 全部钥匙（管理端） */
    List<ApiKey> listAll();

    /**
     * 用明文解析并校验（哈希比对 / 状态 / 过期）。校验通过返回记录；无效抛 {@link org.example.seedancegenarate.exception.ApiException}。
     */
    ApiKey resolveAndValidate(String plainKey);

    /** 记录最后使用时间 */
    void markUsed(ApiKey key);
}
