package org.example.seedancegenarate.service;

/**
 * 接入文档内容服务：单一来源 = 类路径下的 api-docs.md，
 * 管理页（session 鉴权）与对外 API（key 鉴权）都从这里读，不维护两份。
 */
public interface ApiDocService {

    /** 原始 Markdown 内容；资源缺失时返回空串 */
    String content();
}
