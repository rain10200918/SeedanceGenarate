package org.example.seedancegenarate.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.example.seedancegenarate.config.OssConfig;
import org.example.seedancegenarate.context.UserContext;
import org.example.seedancegenarate.dto.OptimizePromptRequest;
import org.example.seedancegenarate.dto.TextToVideoRequest;
import org.example.seedancegenarate.dto.VideoOptionsResponse;
import org.example.seedancegenarate.engine.ModelSpec;
import org.example.seedancegenarate.engine.VideoEngine;
import org.example.seedancegenarate.engine.VideoEngineRegistry;
import org.example.seedancegenarate.entity.Result;
import org.example.seedancegenarate.entity.VideoTask;
import org.example.seedancegenarate.service.ArtifactStorage;
import org.example.seedancegenarate.service.ModelAccessService;
import org.example.seedancegenarate.service.OssService;
import org.example.seedancegenarate.service.ApiDocService;
import org.example.seedancegenarate.service.PromptContext;
import org.example.seedancegenarate.service.PromptOptimizeService;
import org.example.seedancegenarate.service.TaskEtaService;
import org.example.seedancegenarate.service.VideoSubmitService;
import org.example.seedancegenarate.service.VideoTaskService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;


@RestController
@RequestMapping("/api/video")
@RequiredArgsConstructor
public class VideoController {
    private final VideoEngineRegistry videoEngineRegistry;
    private final VideoTaskService videoTaskService;
    private final OssService ossService;
    private final PromptOptimizeService promptOptimizeService;
    private final ModelAccessService modelAccessService;
    private final VideoSubmitService videoSubmitService;
    private final ArtifactStorage artifactStorage;
    private final OssConfig ossConfig;
    private final ApiDocService apiDocService;
    private final TaskEtaService taskEtaService;

    /** 默认提供方；请求未显式指定 provider 时使用 */
    @Value("${video.default-provider:seedance}")
    private String defaultProvider;

    /**
     * 多图参考生成视频（支持本地文件与历史图片 URL 混合）
     *
     * POST
     * /api/video/image2video
     *
     * @param images     本地参考图（multipart，可多张）
     * @param imageUrls  历史图片 URL（FormData 多值），仅接受本系统 OSS 域名地址（防 SSRF）
     * @param imageOrder 图片顺序标记（file/url 逐位置），保序归并；缺省则本地在前、历史在后
     */
    @PostMapping("/image2video")
    public Result<?> image2video(
            @RequestParam(value = "images", required = false)
            MultipartFile[] images,
            @RequestParam(value = "videos", required = false)
            MultipartFile[] videos,
            @RequestParam(value = "audios", required = false)
            MultipartFile[] audios,
            @RequestParam("prompt")
            String prompt,
            @RequestParam(
                    value = "duration",
                    defaultValue = "8"
            )
            Integer duration,
            @RequestParam(
                    value = "ratio",
                    defaultValue = "16:9"
            )
            String ratio,
            @RequestParam(value = "provider", required = false)
            String provider,
            @RequestParam(value = "model", required = false)
            String model,
            @RequestParam(value = "megapixels", required = false)
            Double megapixels,
            @RequestParam(value = "imageUrls", required = false)
            List<String> imageUrls,
            @RequestParam(value = "imageOrder", required = false)
            List<String> imageOrder,
            @RequestParam(value = "videoUrls", required = false)
            List<String> videoUrls,
            @RequestParam(value = "audioUrls", required = false)
            List<String> audioUrls
    ) throws Exception {
        Long userId = UserContext.requireUserId();
        // 闸门在传图副作用之前（提交编排内会再校验一次）
        videoSubmitService.validate(provider, model);
        // 本地文件上传；历史 URL 白名单校验后按顺序归并（顺序 = <Picture N>）
        List<String> imagePaths = new ArrayList<>();
        if (images != null) {
            for (MultipartFile image : images) {
                String url = ossService.upload(image);
                imagePaths.add(url);
            }
        }
        List<String> urls = imageUrls == null ? List.of() : imageUrls.stream().map(u -> validateMediaUrl(u, "图片")).toList();
        imagePaths = mergeImagePaths(imagePaths, urls, imageOrder);
        // 参考视频/音频：本地文件上传 OSS + 历史 URL 白名单校验，按「本地在前、URL 在后」归并
        List<String> videoPaths = uploadRefFiles(videos, videoUrls, "视频");
        List<String> audioPaths = uploadRefFiles(audios, audioUrls, "音频");
        VideoTask task = videoSubmitService.submit(new VideoSubmitService.SubmitRequest(
                userId, provider, model, prompt, imagePaths, videoPaths, audioPaths,
                duration, ratio, megapixels, null));
        return Result.success(task);
    }

    /** 参考视频/音频归并：本地文件（MultipartFile → OSS）在前，历史 URL（白名单校验）在后；数量 ≤2 不引入 order 标签 */
    private List<String> uploadRefFiles(MultipartFile[] files, List<String> urls, String label) throws Exception {
        List<String> paths = new ArrayList<>();
        if (files != null) {
            for (MultipartFile file : files) {
                paths.add(ossService.upload(file));
            }
        }
        if (urls != null) {
            for (String url : urls) {
                paths.add(validateMediaUrl(url, label));
            }
        }
        return paths;
    }

    /**
     * 历史参考素材 URL 白名单校验：必须 http(s) 且 host 为本系统 OSS 域名。
     * URL 会被引擎端下载，不校验等于开放任意地址让后端打内网（SSRF）。
     *
     * @param label 素材类型标签（图片 / 视频 / 音频），用于错误提示
     */
    private String validateMediaUrl(String url, String label) {
        if (!StringUtils.hasText(url)) {
            throw new RuntimeException(label + "地址不能为空");
        }
        try {
            URI uri = new URI(url.trim());
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                throw new RuntimeException(label + "地址必须为 http(s)");
            }
            String allowedHost = hostOf(ossConfig.getDomain());
            if (StringUtils.hasText(allowedHost) && !allowedHost.equalsIgnoreCase(uri.getHost())) {
                throw new RuntimeException(label + "地址必须来自本系统存储");
            }
        } catch (URISyntaxException e) {
            throw new RuntimeException(label + "地址不合法");
        }
        return url.trim();
    }

    /**
     * 按 imageOrder 保序归并本地与历史图片；标记缺失/数量不符时回退「本地在前、历史在后」。
     */
    private List<String> mergeImagePaths(List<String> filePaths, List<String> urls, List<String> imageOrder) {
        if (imageOrder == null || imageOrder.isEmpty() || imageOrder.size() != filePaths.size() + urls.size()) {
            List<String> merged = new ArrayList<>(filePaths);
            merged.addAll(urls);
            return merged;
        }
        List<String> files = new ArrayList<>(filePaths);
        List<String> urlQueue = new ArrayList<>(urls);
        List<String> merged = new ArrayList<>();
        for (String tag : imageOrder) {
            if ("url".equalsIgnoreCase(tag) && !urlQueue.isEmpty()) {
                merged.add(urlQueue.remove(0));
            } else if (!files.isEmpty()) {
                merged.add(files.remove(0));
            }
        }
        merged.addAll(files);
        merged.addAll(urlQueue);
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

    /**
     * 文生视频（纯文本提示词，无参考图）
     *
     * POST
     * /api/video/text2video
     */
    @PostMapping("/text2video")
    public Result<?> text2video(
            @RequestBody TextToVideoRequest request
    ) throws Exception {
        Long userId = UserContext.requireUserId();
        String prompt = request.getPrompt() == null ? "" : request.getPrompt().trim();
        if (prompt.isEmpty()) {
            throw new RuntimeException("提示词不能为空");
        }
        Integer duration = request.getDuration() == null ? 8 : request.getDuration();
        String ratio = (request.getRatio() == null || request.getRatio().isBlank())
                ? "16:9"
                : request.getRatio();
        VideoTask task = videoSubmitService.submit(new VideoSubmitService.SubmitRequest(
                userId, request.getProvider(), request.getModel(), prompt,
                List.of(), List.of(), List.of(), duration, ratio, null, null));
        return Result.success(task);
    }

    /**
     * API 接入文档（原始 Markdown）：登录用户可见，与对外 API 的 /api/v1/videos/docs 同一份资源。
     *
     * GET
     * /api/video/api-docs
     */
    @GetMapping("/api-docs")
    public Result<String> apiDocs() {
        UserContext.requireUserId();
        return Result.success(apiDocService.content());
    }

    /**
     * 优化视频提示词
     * 由后端代理调用大模型，API Key 仅保存在后端，不下发前端
     *
     * POST
     * /api/video/optimize-prompt
     */
    @PostMapping("/optimize-prompt")
    public Result<String> optimizePrompt(
            @RequestBody OptimizePromptRequest request
    ) throws Exception {
        UserContext.requireUserId();
        String prompt = request.getPrompt() == null ? "" : request.getPrompt().trim();
        if (prompt.isEmpty()) {
            throw new RuntimeException("请先输入提示词");
        }
        return Result.success(promptOptimizeService.optimize(prompt,
                new PromptContext(request.getModel(), request.getImageCount(),
                        request.getVideoCount(), request.getAudioCount(),
                        request.getDuration(), request.getRatio())));
    }

    /**
     * 可选的生成提供方与模型能力（驱动前端选择器）
     *
     * GET
     * /api/video/options
     */
    @GetMapping("/options")
    public Result<VideoOptionsResponse> options() {
        UserContext.requireUserId();
        // 普通用户只看到开放的模型；管理员看到全部（带 open 标记，便于开放前自测）
        boolean admin = UserContext.isAdmin();
        // 全量覆盖一次拿完：原先对每个模型调一次 isOpen()，一次 /options 就是七八条单行查询
        Map<String, Boolean> overrides = modelAccessService.currentOverrides();
        boolean defaultOpen = modelAccessService.defaultOpen();
        List<VideoOptionsResponse.ProviderOption> providers = videoEngineRegistry.all().stream()
                .map(engine -> new VideoOptionsResponse.ProviderOption(
                        engine.provider(),
                        engine.displayName(),
                        engine.models().stream()
                                .map(spec -> toModelOption(spec, overrides, defaultOpen))
                                .filter(model -> admin || model.open())
                                .toList()
                ))
                // 普通用户看不到「模型全被关闭」的提供方——留着空模型列表会导致前端
                // 默认选中它后无可选模型（管理员模型全可见，天然不会被过滤）
                .filter(provider -> admin || !provider.models().isEmpty())
                // 默认提供方排最前，其余按标识稳定排序
                .sorted(Comparator.comparingInt(
                                (VideoOptionsResponse.ProviderOption p) -> p.provider().equals(defaultProvider) ? 0 : 1)
                        .thenComparing(VideoOptionsResponse.ProviderOption::provider))
                .toList();
        // 默认提供方若被「模型全关闭」过滤掉了，回退到列表第一个，避免前端选中不存在的提供方
        String effectiveDefault = providers.stream()
                .anyMatch(p -> p.provider().equals(defaultProvider))
                ? defaultProvider
                : providers.stream().findFirst().map(VideoOptionsResponse.ProviderOption::provider).orElse(defaultProvider);
        return Result.success(new VideoOptionsResponse(effectiveDefault, providers));
    }

    private VideoOptionsResponse.ModelOption toModelOption(ModelSpec spec,
                                                           Map<String, Boolean> overrides,
                                                           boolean defaultOpen) {
        // durations 为空表示区间可选，展开成离散值供前端直接渲染；图片模型（无时长）则给空列表
        List<Integer> durations;
        if (!spec.durations().isEmpty()) {
            durations = spec.durations();
        } else if (spec.durationMax() >= spec.durationMin() && spec.durationMax() > 0) {
            durations = IntStream.rangeClosed(spec.durationMin(), spec.durationMax()).boxed().toList();
        } else {
            durations = List.of();
        }
        return new VideoOptionsResponse.ModelOption(
                spec.model(), spec.label(), spec.needImages(),
                spec.imageMin(), spec.imageMax(), spec.ratios(), durations,
                spec.outputType().name(), spec.megapixels(),
                overrides.getOrDefault(spec.model(), defaultOpen),
                spec.videoMax(), spec.audioMax(), spec.needImageOrVideo()
        );
    }


    /**
     * 分页查询视频生成任务列表
     *
     * GET
     * /api/video/tasks?current=1&size=10
     */
    @GetMapping("/tasks")
    public Result<Page<VideoTask>> tasks(
            @RequestParam(
                    value = "current",
                    defaultValue = "1"
            )
            Long current,
            @RequestParam(
                    value = "size",
                    defaultValue = "10"
            )
            Long size,
            @RequestParam(
                    value = "status",
                    required = false
            )
            String status
    ) {
        long pageCurrent = Math.max(current, 1L);
        long pageSize = Math.min(
                Math.max(size, 1L),
                100L
        );
        Page<VideoTask> page = new Page<>(
                pageCurrent,
                pageSize
        );
        LambdaQueryWrapper<VideoTask> wrapper = new LambdaQueryWrapper<>();
        if (!UserContext.isAdmin()) {
            wrapper.eq(VideoTask::getUserId, UserContext.requireUserId());
        }
        if (StringUtils.hasText(status)) {
            wrapper.eq(VideoTask::getStatus, status.trim());
        }
        wrapper.orderByDesc(VideoTask::getUpdateTime)
                .orderByDesc(VideoTask::getId);
        // 列表瘦身：只取列表列（列表展示提示词/缩略图，砍掉 error_msg 大文本）
        wrapper.select(VideoTask::getId, VideoTask::getTaskId, VideoTask::getBizTaskId, VideoTask::getUserId,
                VideoTask::getStatus, VideoTask::getVideoUrl, VideoTask::getImages,
                VideoTask::getDuration, VideoTask::getRatio, VideoTask::getProvider, VideoTask::getNodeId,
                VideoTask::getModel, VideoTask::getOutputType, VideoTask::getCostAmount,
                VideoTask::getPrompt, VideoTask::getCreateTime, VideoTask::getUpdateTime);
        return Result.success(videoTaskService.page(page, wrapper));
    }

    /**
     * 任务预计完成时间（ETA）：排队位置 + 剩余估算。
     * 前端按字段是否有值渲染，不感知提供方能力。
     *
     * GET
     * /api/video/task/{taskId}/eta
     */
    @GetMapping("/task/{taskId}/eta")
    public Result<TaskEtaService.TaskEta> taskEta(@PathVariable String taskId) {
        UserContext.requireUserId();
        VideoTask task = findOwnedTask(taskId);
        if (task == null) {
            throw new RuntimeException("任务不存在");
        }
        return Result.success(taskEtaService.estimate(task));
    }

    /**
     * 查询视频生成状态
     *
     * GET
     * /api/video/task/{taskId}
     */
    @GetMapping("/task/{taskId}")
    public Result<?> task(@PathVariable String taskId) {
        LambdaQueryWrapper<VideoTask> taskWrapper = Wrappers.<VideoTask>lambdaQuery()
                .eq(VideoTask::getTaskId, taskId);
        if (!UserContext.isAdmin()) {
            taskWrapper.eq(VideoTask::getUserId, UserContext.requireUserId());
        }
        VideoTask task = videoTaskService.getOne(
                taskWrapper,
                false
        );
        if (task == null) {
            throw new RuntimeException("任务不存在");
        }
        // 只读库：远端轮询已由后台推进器（VideoTaskPoller）统一负责并落库，实时变化经 SSE
        // （GET /api/video/stream）推送给前端。此处不再触发远端轮询，避免每次客户端查询都打远端。
        return Result.success(task);
    }

    /**
     * 下载生成的视频到本地
     * 按 taskId 从数据库取本地文件，附带 Content-Disposition 触发浏览器下载
     *
     * GET
     * /api/video/download/{taskId}
     */
    @GetMapping("/download/{taskId}")
    public void download(
            @PathVariable String taskId,
            HttpServletResponse response
    ) throws Exception {
        UserContext.requireUserId();
        VideoTask task = findOwnedTask(taskId);
        if (task == null || task.getVideoUrl() == null || task.getVideoUrl().isBlank()) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        if (hasOssArtifact(task)) {
            String name = "seedance-" + task.businessTaskId() + extensionOf(task.getVideoUrl());
            response.sendRedirect(artifactStorage.createSignedDownloadUrl(
                    task.getArtifactKey(), name, java.time.Duration.ofSeconds(ossConfig.getSignedUrlTtlSeconds())));
            return;
        }
        copyLegacyLocalArtifact(task.getVideoUrl(), response, true, task.businessTaskId());
    }

    @GetMapping("/{fileName}")
    public void video(
            @PathVariable String fileName,
            HttpServletResponse response
    ) throws Exception {
        UserContext.requireUserId();
        LambdaQueryWrapper<VideoTask> videoWrapper = Wrappers.<VideoTask>lambdaQuery()
                .and(wrapper -> wrapper
                        .eq(VideoTask::getVideoUrl, fileName)
                        .or()
                        .eq(VideoTask::getVideoUrl, "data/videos/" + fileName)
                );
        if (!UserContext.isAdmin()) {
            videoWrapper.eq(VideoTask::getUserId, UserContext.requireUserId());
        }
        VideoTask task = videoTaskService.getOne(videoWrapper, false);
        if (task == null) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        if (hasOssArtifact(task)) {
            response.sendRedirect(artifactStorage.createSignedGetUrl(
                    task.getArtifactKey(), java.time.Duration.ofSeconds(ossConfig.getSignedUrlTtlSeconds())));
            return;
        }
        copyLegacyLocalArtifact(task.getVideoUrl(), response, false, null);
    }

    /** 查询当前用户有权访问的任务；迁移期兼容旧 task_id。 */
    private VideoTask findOwnedTask(String taskId) {
        LambdaQueryWrapper<VideoTask> wrapper = Wrappers.<VideoTask>lambdaQuery()
                .and(w -> w.eq(VideoTask::getBizTaskId, taskId)
                        .or()
                        .eq(VideoTask::getTaskId, taskId));
        if (!UserContext.isAdmin()) {
            wrapper.eq(VideoTask::getUserId, UserContext.requireUserId());
        }
        return videoTaskService.getOne(wrapper, false);
    }

    private boolean hasOssArtifact(VideoTask task) {
        return "OSS".equals(task.getArtifactStorageType())
                && StringUtils.hasText(task.getArtifactKey());
    }

    /** 兼容迁移前的 data/videos/ 文件；新产物不再进入此路径。 */
    private void copyLegacyLocalArtifact(String stored, HttpServletResponse response,
                                         boolean attachment, String taskId) throws Exception {
        String fileName = stored.startsWith("data/videos/")
                ? stored.substring("data/videos/".length())
                : stored;
        Path path = Paths.get("data/videos/", fileName);
        if (!Files.exists(path)) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        response.setContentType(contentTypeOf(fileName));
        if (attachment) {
            response.setHeader("Content-Disposition",
                    "attachment; filename=\"seedance-" + taskId + extensionOf(fileName) + "\"");
        }
        Files.copy(path, response.getOutputStream());
    }

    /** 按文件扩展名推断 Content-Type（视频 / 图片通用），未知时回退 video/mp4 */
    private String contentTypeOf(String fileName) {
        String lower = fileName.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".bmp")) return "image/bmp";
        if (lower.endsWith(".webm")) return "video/webm";
        if (lower.endsWith(".mov")) return "video/quicktime";
        if (lower.endsWith(".mkv")) return "video/x-matroska";
        return "video/mp4";
    }

    /** 取文件扩展名（含点），无扩展名回退 .mp4 */
    private String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot >= 0 ? fileName.substring(dot) : ".mp4";
    }
}
