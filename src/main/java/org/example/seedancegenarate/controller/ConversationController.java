package org.example.seedancegenarate.controller;

import lombok.RequiredArgsConstructor;
import org.example.seedancegenarate.context.UserContext;
import org.example.seedancegenarate.dto.ConversationMessageView;
import org.example.seedancegenarate.dto.ConversationTurnView;
import org.example.seedancegenarate.dto.ConversationView;
import org.example.seedancegenarate.dto.SendMessageRequest;
import org.example.seedancegenarate.entity.Result;
import org.example.seedancegenarate.exception.BusinessException;
import org.example.seedancegenarate.service.ConversationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 对话式创作。所有读写都以当前登录用户为属主，别人的对话查不到（404）。 */
@RestController
@RequestMapping("/api/conversations")
@RequiredArgsConstructor
public class ConversationController {
    private final ConversationService conversationService;

    @GetMapping
    public Result<List<ConversationView>> list(@RequestParam(defaultValue = "false") boolean includeArchived) {
        return Result.success(conversationService.list(UserContext.requireUserId(), includeArchived));
    }

    @PostMapping
    public Result<ConversationView> create(@RequestBody(required = false) CreateRequest request) {
        return Result.success(conversationService.create(UserContext.requireUserId(),
                request == null ? null : request.title()));
    }

    /** 改名 / 归档 / 取消归档，给了哪个字段改哪个 */
    @PatchMapping("/{id}")
    public Result<ConversationView> patch(@PathVariable Long id, @RequestBody PatchRequest request) {
        Long userId = UserContext.requireUserId();
        ConversationView view = null;
        if (request != null && request.title() != null) {
            view = conversationService.rename(userId, id, request.title());
        }
        if (request != null && request.archived() != null) {
            view = conversationService.setArchived(userId, id, request.archived());
        }
        if (view == null) {
            throw BusinessException.badRequest("至少提供一个要修改的字段");
        }
        return Result.success(view);
    }

    @GetMapping("/{id}/messages")
    public Result<List<ConversationMessageView>> messages(@PathVariable Long id,
                                                          @RequestParam(required = false) Integer beforeSeq,
                                                          @RequestParam(defaultValue = "50") int limit) {
        return Result.success(conversationService.messages(UserContext.requireUserId(), id, beforeSeq, limit));
    }

    /** 发一条消息：同步等 Agent 整理（最长约 100 秒），返回整轮气泡 */
    @PostMapping("/{id}/messages")
    public Result<ConversationTurnView> send(@PathVariable Long id, @RequestBody SendMessageRequest request) {
        return Result.success(conversationService.send(UserContext.requireUserId(), id, request));
    }

    public record CreateRequest(String title) {
    }

    public record PatchRequest(String title, Boolean archived) {
    }
}
