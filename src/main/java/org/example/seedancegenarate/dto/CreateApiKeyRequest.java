package org.example.seedancegenarate.dto;

import lombok.Data;

/**
 * 创建 API Key 请求（管理员操作）：指定属主用户，可选用途备注与 webhook 回调地址。
 */
@Data
public class CreateApiKeyRequest {
    /** 属主用户 ID */
    private Long userId;
    /** 用途备注 */
    private String name;
    /** webhook 回调地址 */
    private String callbackUrl;
}
