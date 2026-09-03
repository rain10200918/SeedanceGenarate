package org.example.seedancegenarate.dto;

import java.util.List;

/**
 * 对话里发一条消息。参考素材只收 URL（本地图片先进素材库再发）；
 * generation 是这一轮生成用的参数，由前端的 composer 选好。
 */
public record SendMessageRequest(
        String clientMsgId,
        String content,
        List<Attachment> attachments,
        /** AGENT：先整理提示词再生成；DIRECT：按原话直接生成 */
        String mode,
        Generation generation) {

    public record Attachment(String type, String url) {
    }

    public record Generation(String provider, String model, String ratio, Integer duration, Double megapixels) {
    }
}
