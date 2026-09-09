package org.example.seedancegenarate.dto;

import java.time.Instant;
import java.util.Map;

/** OSS HTML form POST 直传参数；fields 原样放入 multipart/form-data 后再放 file。 */
public record ApiAssetUploadUrlResponse(
        String method,
        String uploadUrl,
        Map<String, String> fields,
        String assetUrl,
        Instant uploadExpiresAt,
        Instant assetExpiresAt
) {
}
