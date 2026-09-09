package org.example.seedancegenarate.service;

import org.example.seedancegenarate.dto.ApiAssetUploadUrlRequest;
import org.example.seedancegenarate.dto.ApiAssetUploadUrlResponse;

public interface ApiAssetUploadService {
    ApiAssetUploadUrlResponse issue(Long ownerId, ApiAssetUploadUrlRequest request);
}
