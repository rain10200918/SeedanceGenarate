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

/**
 * 【对外 API - 媒体素材上传授权控制器】
 * <p>
 * 业务定位：
 * 外部开发者在提交图生视频、图生图或多参考素材任务前，需要先上传图片、视频或音频。
 * 平台不让外部开发者将巨大的二进制文件直接 POST 到本 API 服务器（避免挤爆服务器网络带宽与内存），
 * 而是由本接口签发一个带有效期的「阿里云 OSS 直传预签名 URL（Presigned Upload URL）」。
 * 开发者拿到该 URL 后直接将文件 PUT 到 OSS，上传完成后再用 OSS 的地址调用生成接口。
 * <p>
 * 访问前缀：/api/v1/assets
 */
@RestController
@RequestMapping("/api/v1/assets")
@RequiredArgsConstructor
public class ApiAssetController {

    /** 素材直传服务：负责生成带有防伪签名、限时有效、指定存储路径的 OSS 上传凭证 */
    private final ApiAssetUploadService uploadService;

    /**
     * 申请媒体素材直传授权与目标 URL
     * <p>
     * 接口路径：POST /api/v1/assets/upload-url
     *
     * @param request 包含要上传的文件名、文件格式（MIME-Type，如 image/png）、文件体积预估等
     * @return ApiAssetUploadUrlResponse 返回供客户端直传 OSS 的 PUT URL、最终访问地址以及授权过期时间
     */
    @PostMapping("/upload-url")
    public ApiAssetUploadUrlResponse uploadUrl(@RequestBody ApiAssetUploadUrlRequest request) {
        // 从当前登录会话中获取操作人的用户 ID，签发专属于该用户的素材上传通道
        return uploadService.issue(UserContext.requireUserId(), request);
    }
}

