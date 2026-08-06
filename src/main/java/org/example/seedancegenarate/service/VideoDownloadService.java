package org.example.seedancegenarate.service;

import com.baomidou.mybatisplus.extension.service.IService;

public interface VideoDownloadService {
    String download(String url) throws Exception;
}
