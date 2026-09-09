package org.example.seedancegenarate.dto;

/** 直传凭证申请；P0 仅支持 JPEG/PNG/WebP，且文件不超过 30 MiB。 */
public record ApiAssetUploadUrlRequest(String filename, String contentType, Long sizeBytes) {
}
