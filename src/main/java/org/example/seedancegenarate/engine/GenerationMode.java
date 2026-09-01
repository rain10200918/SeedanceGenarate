package org.example.seedancegenarate.engine;

/**
 * 生成模式，与具体提供方无关。
 */
public enum GenerationMode {
    /** 文生视频 */
    TEXT_TO_VIDEO,
    /** 图生视频 */
    IMAGE_TO_VIDEO,
    /** 文生图 */
    TEXT_TO_IMAGE,
    /** 图生图 */
    IMAGE_TO_IMAGE,
    /** 文生音频/音乐 */
    TEXT_TO_AUDIO;

    /**
     * 由「是否有参考图」×「输出类型」派生任务类型 —— 这两个正交事实（输入看 images 是否为空、
     * 输出看模型 {@link OutputType}）是唯一来源，本枚举只作派生视图，不单独落库。
     */
    public static GenerationMode of(boolean hasImage, OutputType output) {
        if (output == OutputType.AUDIO) {
            return TEXT_TO_AUDIO;
        }
        boolean image = output == OutputType.IMAGE;
        if (hasImage) {
            return image ? IMAGE_TO_IMAGE : IMAGE_TO_VIDEO;
        }
        return image ? TEXT_TO_IMAGE : TEXT_TO_VIDEO;
    }
}
