package org.example.seedancegenarate.controller;

import lombok.RequiredArgsConstructor;
import org.example.seedancegenarate.context.UserContext;
import org.example.seedancegenarate.dto.ApiAssetUploadUrlRequest;
import org.example.seedancegenarate.dto.ApiAssetUploadUrlResponse;
import org.example.seedancegenarate.service.ApiAssetUploadService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/assets")
@RequiredArgsConstructor
public class ApiAssetController {
    private final ApiAssetUploadService uploadService;

    @PostMapping("/upload-url")
    public ApiAssetUploadUrlResponse uploadUrl(@RequestBody ApiAssetUploadUrlRequest request) {
        return uploadService.issue(UserContext.requireUserId(), request);
    }
}
