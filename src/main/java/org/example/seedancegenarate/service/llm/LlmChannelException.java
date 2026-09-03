package org.example.seedancegenarate.service.llm;

/**
 * 一次 LLM 通道调用失败。路由只看一个布尔：<b>能不能切下一条</b>。
 * <p>
 * 能切的是「快失败」——连接被拒 / 连接超时 / 4xx / 5xx / 响应解析不了 / 内容为空，几毫秒到几秒就回，
 * 前端 120 秒预算还在。<b>读超时不能切</b>：时间已经花完了，切过去也来不及，只会让用户从 100s 等到 120s
 * 然后照样失败，还把「内容较长请缩短」这句用户能自救的提示盖掉（人已拍板：超时直接失败）。
 * <p>
 * {@link #getMessage()} 是给用户看的话；{@link #reason()} 是给日志看的短因，<b>不含 URL 和密钥</b>。
 */
public class LlmChannelException extends RuntimeException {

    private final boolean failoverable;
    private final String reason;

    private LlmChannelException(String userMessage, String reason, boolean failoverable, Throwable cause) {
        super(userMessage, cause);
        this.failoverable = failoverable;
        this.reason = reason;
    }

    /** 快失败：路由可以切下一条 */
    public static LlmChannelException failoverable(String reason, Throwable cause) {
        return new LlmChannelException("提示词优化服务调用失败，请稍后再试", reason, true, cause);
    }

    /** 读超时：直接失败，且保留用户能自救的那句话 */
    public static LlmChannelException readTimeout(Throwable cause) {
        return new LlmChannelException(
                "提示词优化超时：内容较长时模型生成需要更久，请缩短提示词后重试",
                "read timeout", false, cause);
    }

    /** 不可切也不是超时：线程被中断等 */
    public static LlmChannelException terminal(String reason, Throwable cause) {
        return new LlmChannelException("提示词优化服务调用失败，请稍后再试", reason, false, cause);
    }

    public boolean failoverable() {
        return failoverable;
    }

    public String reason() {
        return reason;
    }
}
