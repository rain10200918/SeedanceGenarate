package org.example.seedancegenarate.engine;

/**
 * 供应商适配器能够明确证明请求尚未入队。
 * <p>
 * 只有这种失败才允许重用同一 generation attempt 自动重投；
 * 超时、断连、5xx、空响应都不属于本异常。
 */
public class SubmissionNotAcceptedException extends Exception {
    public SubmissionNotAcceptedException(String message) {
        super(message);
    }

    public SubmissionNotAcceptedException(String message, Throwable cause) {
        super(message, cause);
    }
}
