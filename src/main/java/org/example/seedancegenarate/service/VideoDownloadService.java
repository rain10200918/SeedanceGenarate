package org.example.seedancegenarate.service;

/** 将提供方远端产物转存为本系统可长期访问的 OSS 对象。 */
public interface VideoDownloadService {

    /** 同一 attempt 重放复用 key；不同 attempt 必须写入不同 key，避免迟到上传覆盖新产物。 */
    DownloadedArtifact download(String remoteUrl, String bizTaskId, Long attemptId,
                                String provider) throws Exception;

    record DownloadedArtifact(String mediaName, ArtifactStorage.StoredArtifact artifact) {
    }
}
