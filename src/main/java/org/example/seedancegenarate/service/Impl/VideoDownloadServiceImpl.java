package org.example.seedancegenarate.service.Impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.example.seedancegenarate.entity.VideoTask;
import org.example.seedancegenarate.mapper.VideoTaskMapper;
import org.example.seedancegenarate.service.VideoDownloadService;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.UUID;

@Service
public class VideoDownloadServiceImpl extends ServiceImpl<VideoTaskMapper, VideoTask> implements VideoDownloadService {
    private final String VIDEO_PATH = "data/videos/";

    @Override
    public String download(String url) throws Exception {
        Path dir = Paths.get(VIDEO_PATH);
        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
        }
        String filename = UUID.randomUUID() + resolveExtension(url);
        Path target = dir.resolve(filename);
        try(InputStream inputStream = new URL(url).openStream()){
            Files.copy(
                    inputStream,
                    target,
                    StandardCopyOption.REPLACE_EXISTING
            );
        }
        return target.toString();
    }

    /**
     * 从下载地址推断扩展名，保留真实媒介类型（图片 / 视频）。
     * ComfyUI 的 /view 把文件名放在 filename 查询参数；Seedance / 普通地址放在路径末段。
     * 识别不到已知媒介扩展名时回退 .mp4（保持历史行为）。
     */
    private String resolveExtension(String url) {
        if (url == null) {
            return ".mp4";
        }
        String candidate = extractFilenameParam(url);
        if (candidate == null) {
            int q = url.indexOf('?');
            candidate = q >= 0 ? url.substring(0, q) : url;
        }
        int dot = candidate.lastIndexOf('.');
        int slash = Math.max(candidate.lastIndexOf('/'), candidate.lastIndexOf('\\'));
        if (dot > slash) {
            String ext = candidate.substring(dot).toLowerCase(Locale.ROOT);
            if (ext.matches("\\.(mp4|webm|mov|mkv|gif|png|jpg|jpeg|webp|bmp)")) {
                return ext;
            }
        }
        return ".mp4";
    }

    /** 取 ComfyUI /view?filename=xxx.png 里的 filename 值（URL 解码后） */
    private String extractFilenameParam(String url) {
        int idx = url.indexOf("filename=");
        if (idx < 0) {
            return null;
        }
        int start = idx + "filename=".length();
        int end = url.indexOf('&', start);
        String raw = end >= 0 ? url.substring(start, end) : url.substring(start);
        return URLDecoder.decode(raw, StandardCharsets.UTF_8);
    }
}
