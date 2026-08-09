package org.example.seedancegenarate.service;

/** 将提供方远端产物转存为本系统可长期访问的 OSS 对象。 */
public interface VideoDownloadService {

    DownloadedArtifact download(String remoteUrl, String bizTaskId) throws Exception;

    record DownloadedArtifact(String mediaName, ArtifactStorage.StoredArtifact artifact) {
    }
}
