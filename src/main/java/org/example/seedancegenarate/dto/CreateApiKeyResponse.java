package org.example.seedancegenarate.dto;

/**
 * 创建 API Key 响应：明文 key 只出现这一次，务必立即保存；记录为裁剪后的视图（不含敏感字段）。
 */
public record CreateApiKeyResponse(ApiKeyView apiKey, String plainKey) {
}
