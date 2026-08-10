package org.example.seedancegenarate.engine.comfyui;

import java.util.List;

/**
 * 已上传到目标 ComfyUI 节点的参考素材文件名，顺序与模板里的 &lt;Picture 1..N&gt; / &lt;Video 1..N&gt; / &lt;Audio 1..N&gt; 一一对应。
 * 由 {@link ComfyUiEngine} 在提交前按类型上传后构造，传给 {@link WorkflowBuilder#build} 供各模型重建加载节点。
 */
public record ReferenceFiles(
        List<String> images,
        List<String> videos,
        List<String> audios
) {
    public ReferenceFiles {
        images = images == null ? List.of() : images;
        videos = videos == null ? List.of() : videos;
        audios = audios == null ? List.of() : audios;
    }
}
