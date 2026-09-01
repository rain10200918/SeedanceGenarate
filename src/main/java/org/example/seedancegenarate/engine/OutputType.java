package org.example.seedancegenarate.engine;

/**
 * 生成产物的媒介类型。用于 /options 告知前端如何渲染结果（&lt;video&gt; vs &lt;img&gt;），
 * 以及是否展示时长等视频专有参数。
 */
public enum OutputType {
    /** 视频（mp4） */
    VIDEO,
    /** 图片（png / jpg …） */
    IMAGE,
    /** 音频（mp3) */
    AUDIO,
}
