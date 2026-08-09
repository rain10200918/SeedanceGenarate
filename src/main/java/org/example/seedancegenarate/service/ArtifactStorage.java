package org.example.seedancegenarate.service;

import java.io.InputStream;
import java.time.Duration;

/**
 * 生成产物的对象存储抽象。业务层只保存 object key，不依赖本地磁盘或具体云厂商。
 */
public interface ArtifactStorage {

    /**
     * 将远端产物流式写入对象存储。
     * 返回的 key 是稳定的业务对象标识，不是公开下载 URL。
     */
    StoredArtifact put(String objectKey, InputStream input, String contentType, long contentLength)
            throws Exception;

    /** 为已鉴权的读取请求生成短期内联读取地址。 */
    String createSignedGetUrl(String objectKey, Duration ttl) throws Exception;

    /** 为已鉴权的下载请求生成短期附件下载地址。 */
    String createSignedDownloadUrl(String objectKey, String fileName, Duration ttl) throws Exception;

    /** 判断对象是否存在，用于重试和迁移幂等。 */
    boolean exists(String objectKey) throws Exception;

    record StoredArtifact(String objectKey, String contentType, long contentLength, String etag) {
    }
}
