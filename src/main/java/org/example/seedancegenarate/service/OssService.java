package org.example.seedancegenarate.service;

import org.springframework.web.multipart.MultipartFile;

public interface OssService {
    String upload(MultipartFile file) throws Exception;

    /** 按字节上传（对外 API 传图片 URL 时：下载字节 → 传 OSS） */
    String upload(byte[] bytes, String originalFilename) throws Exception;
}
