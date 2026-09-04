package org.example.seedancegenarate.service;

import lombok.RequiredArgsConstructor;
import org.example.seedancegenarate.config.OssConfig;
import org.example.seedancegenarate.dto.SendMessageRequest;
import org.example.seedancegenarate.exception.BusinessException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;

/**
 * 对话里的参考素材，和生成页 {@code /video/image2video} 同一套规矩：
 * 本地文件跟着请求走，这里传 OSS 换成地址；素材库来的历史地址只认本系统 OSS 域名——引擎会去下载这些地址，
 * 不校验就是让后端替人打任意地址（SSRF）。
 * 图片按 imageOrder（file / url 逐位置）保序归并，标记缺失或数量不符退回「本地在前、历史在后」；视频 / 音频固定本地在前。
 */
@Component
@RequiredArgsConstructor
public class ConversationMediaResolver {

    private final OssService ossService;
    private final OssConfig ossConfig;

    /** 随 multipart 一起来的本地文件；纯 JSON 请求用 {@link #none()} */
    public record LocalFiles(MultipartFile[] images, List<String> imageOrder, MultipartFile[] videos, MultipartFile[] audios) {
        public static LocalFiles none() {
            return new LocalFiles(null, null, null, null);
        }
    }

    /** urlRefs 已经过结构校验（类型 / http(s) / 长度）；返回图片、视频、音频三段拼起来的最终顺序 */
    public List<SendMessageRequest.Attachment> resolve(List<SendMessageRequest.Attachment> urlRefs, LocalFiles files) {
        LocalFiles local = files == null ? LocalFiles.none() : files;
        List<String> images = merge(upload(local.images()), ownStorageUrls(urlRefs, "image", "图片"), local.imageOrder());
        List<String> videos = new ArrayList<>(upload(local.videos()));
        videos.addAll(ownStorageUrls(urlRefs, "video", "视频"));
        List<String> audios = new ArrayList<>(upload(local.audios()));
        audios.addAll(ownStorageUrls(urlRefs, "audio", "音频"));

        List<SendMessageRequest.Attachment> out = new ArrayList<>();
        images.forEach(u -> out.add(new SendMessageRequest.Attachment("image", u)));
        videos.forEach(u -> out.add(new SendMessageRequest.Attachment("video", u)));
        audios.forEach(u -> out.add(new SendMessageRequest.Attachment("audio", u)));
        return out;
    }

    private List<String> upload(MultipartFile[] files) {
        List<String> urls = new ArrayList<>();
        if (files == null) {
            return urls;
        }
        for (MultipartFile file : files) {
            if (file == null || file.isEmpty()) {
                continue;
            }
            try {
                urls.add(ossService.upload(file));
            } catch (Exception e) {
                throw new IllegalStateException("参考素材上传失败，请稍后再试", e);
            }
        }
        return urls;
    }

    private List<String> ownStorageUrls(List<SendMessageRequest.Attachment> refs, String type, String label) {
        List<String> urls = new ArrayList<>();
        if (refs == null) {
            return urls;
        }
        for (SendMessageRequest.Attachment a : refs) {
            if (a != null && type.equals(a.type())) {
                urls.add(requireOwnStorage(a.url(), label));
            }
        }
        return urls;
    }

    /** 历史地址白名单：http(s) 且 host 是本系统 OSS 域名；没配域名（本地开发）只查 scheme */
    String requireOwnStorage(String url, String label) {
        if (!StringUtils.hasText(url)) {
            throw BusinessException.badRequest(label + "地址不能为空");
        }
        URI uri;
        try {
            uri = new URI(url.trim());
        } catch (URISyntaxException e) {
            throw BusinessException.badRequest(label + "地址不合法");
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw BusinessException.badRequest(label + "地址必须为 http(s)");
        }
        String allowedHost = hostOf(ossConfig.getDomain());
        if (StringUtils.hasText(allowedHost) && !allowedHost.equalsIgnoreCase(uri.getHost())) {
            throw BusinessException.badRequest(label + "地址必须来自本系统存储");
        }
        return url.trim();
    }

    /** imageOrder 逐位置写 file / url；标记缺失或数量不符时退回「本地在前、历史在后」 */
    static List<String> merge(List<String> uploaded, List<String> urls, List<String> imageOrder) {
        List<String> merged = new ArrayList<>();
        if (imageOrder == null || imageOrder.size() != uploaded.size() + urls.size()) {
            merged.addAll(uploaded);
            merged.addAll(urls);
            return merged;
        }
        int f = 0;
        int u = 0;
        for (String tag : imageOrder) {
            if ("file".equalsIgnoreCase(tag) && f < uploaded.size()) {
                merged.add(uploaded.get(f++));
            } else if ("url".equalsIgnoreCase(tag) && u < urls.size()) {
                merged.add(urls.get(u++));
            } else {
                merged.clear();
                merged.addAll(uploaded);
                merged.addAll(urls);
                return merged;
            }
        }
        return merged;
    }

    private static String hostOf(String domain) {
        if (!StringUtils.hasText(domain)) {
            return null;
        }
        try {
            return new URI(domain.contains("://") ? domain : "https://" + domain).getHost();
        } catch (URISyntaxException e) {
            return domain;
        }
    }
}
