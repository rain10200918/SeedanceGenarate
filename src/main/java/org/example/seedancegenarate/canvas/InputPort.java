package org.example.seedancegenarate.canvas;

/**
 * 一个输入端口。{@code max} 是该端口可接入的上游数量上限（如模型 imageMax=3 则图片口 max=3），
 * {@code required} 表示不接就不能运行。
 */
public record InputPort(String id, String label, MediaType accepts, boolean required, int max) {

    /** 端口 id 常量：与前端连接点、执行时装配 SubmitRequest 的字段一一对应 */
    public static final String PROMPT = "prompt";
    public static final String IMAGE = "image";
    public static final String VIDEO = "video";
    public static final String AUDIO = "audio";
}
