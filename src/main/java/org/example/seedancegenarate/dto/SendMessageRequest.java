package org.example.seedancegenarate.dto;

import java.util.List;

/**
 * 对话里发一条消息。attachments 是素材库里选的历史地址（只认本系统 OSS 域名）；
 * 本地文件和生成页一样随 multipart 的 images / videos / audios 一起来，由 {@code ConversationMediaResolver} 传 OSS 后归并。
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

    public record Generation(String provider, String model, String ratio, Integer duration, Double megapixels,
                             String resolution) {
        public Generation(String provider,String model,String ratio,Integer duration,Double megapixels) {
            this(provider,model,ratio,duration,megapixels,null);
        }
    }
}
