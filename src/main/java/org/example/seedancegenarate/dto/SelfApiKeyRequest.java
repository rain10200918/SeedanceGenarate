package org.example.seedancegenarate.dto;

import lombok.Data;

/**
 * 自助创建 / 改备注 API Key 的请求体。
 * <p>
 * <b>刻意不含 userId</b>。属主一律取自 {@code UserContext.requireUserId()}——
 * 只要这里有一个 userId 字段，就迟早会有人把它传进 service，
 * 那就是任何人都能给任何账号签发 key，<b>而那把 key 花的是别人的余额</b>。
 * 管理员代建走另一个 DTO（{@link CreateApiKeyRequest}）和另一个 controller。
 */
@Data
public class SelfApiKeyRequest {
    /** 用途备注；不填则由服务端给默认名 */
    private String name;
    /** webhook 回调地址（可选） */
    private String callbackUrl;
}
