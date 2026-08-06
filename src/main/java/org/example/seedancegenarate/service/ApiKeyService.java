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

    /** 全部钥匙（管理端） */
    List<ApiKey> listAll();

    /**
     * 用明文解析并校验（哈希比对 / 状态 / 过期）。校验通过返回记录；无效抛 {@link org.example.seedancegenarate.exception.ApiException}。
     */
    ApiKey resolveAndValidate(String plainKey);

    /** 记录最后使用时间 */
    void markUsed(ApiKey key);
}
