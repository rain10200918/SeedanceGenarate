package org.example.seedancegenarate.agent.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.example.seedancegenarate.agent.application.AgentApplication;
import org.example.seedancegenarate.agent.model.AgentModelGateway;
import org.example.seedancegenarate.config.RateLimitConfig;
import org.example.seedancegenarate.context.UserContext;
import org.example.seedancegenarate.entity.Result;
import org.example.seedancegenarate.exception.BusinessException;
import org.example.seedancegenarate.service.TokenBucketRateLimitService;
import org.example.seedancegenarate.util.TokenUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.multipart.MultipartFile;
import org.example.seedancegenarate.agent.application.AgentDirectApplication;
import org.example.seedancegenarate.agent.generation.AgentDirectGenerationGateway;
import org.example.seedancegenarate.service.ConversationMediaResolver.LocalFiles;
import org.example.seedancegenarate.engine.ModelSpec;
import java.util.List;

@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class AgentController {
    private final AgentApplication app;
    private final AgentDirectApplication direct;
    private final AgentDirectGenerationGateway directModels;
    private final org.example.seedancegenarate.agent.application.AgentWorkspaceApplication workspace;
    private final org.example.seedancegenarate.agent.application.AgentApprovalApplication approvals;
    private final AgentModelGateway models;
    private final AgentStream streams;
    private final TokenBucketRateLimitService rate;
    private static final RateLimitConfig.Bucket COMMANDS=new RateLimitConfig.Bucket(true,20,10,60L);
    public record Create(String title) {}
    public record Cancel(String turnId) {}
    @GetMapping("/channels")
    public Result<List<AgentModelGateway.Channel>> channels() { return Result.success(models.channels()); }
    @GetMapping("/generation-options")
    public Result<List<ModelSpec>> generationOptions() { return Result.success(directModels.models()); }
    @GetMapping("/conversations")
    public Result<List<AgentViews.Conversation>> list() { return Result.success(app.list(UserContext.requireUserId())); }
    @PostMapping("/conversations")
    public Result<AgentViews.Conversation> create(@RequestBody(required=false) Create body) {
        long user=limited(); return Result.success(app.create(user,body==null?null:body.title()));
    }
    @GetMapping("/conversations/{id}")
    public Result<AgentViews.Snapshot> get(@PathVariable long id) { return Result.success(app.snapshot(UserContext.requireUserId(),id)); }
    @DeleteMapping("/conversations/{id}")
    public Result<Void> delete(@PathVariable long id) { app.delete(limited(),id); return Result.success(null); }
    @PostMapping(value="/conversations/{id}/direct",consumes=MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Result<AgentViews.Snapshot> direct(@PathVariable long id,@RequestBody AgentDirectApplication.Request body) {
        long user=limited(); direct.submit(user,id,body,LocalFiles.none()); return Result.success(app.snapshot(user,id));
    }
    @PostMapping(value="/conversations/{id}/direct",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Result<AgentViews.Snapshot> directWithFiles(@PathVariable long id,@RequestPart("payload") AgentDirectApplication.Request body,
            @RequestParam(value="images",required=false) MultipartFile[] images,
            @RequestParam(value="imageOrder",required=false) List<String> imageOrder,
            @RequestParam(value="videos",required=false) MultipartFile[] videos,
            @RequestParam(value="audios",required=false) MultipartFile[] audios) {
        long user=limited(); direct.submit(user,id,body,new LocalFiles(images,imageOrder,videos,audios)); return Result.success(app.snapshot(user,id));
    }
    @PostMapping("/conversations/{id}/interactions/{interactionId}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Result<AgentViews.Snapshot> answer(@PathVariable long id,@PathVariable String interactionId,@RequestBody AgentApplication.Answer body) {
        return Result.success(app.answer(limited(),id,interactionId,body));
    }
    @PostMapping("/conversations/{id}/cancel")
    public Result<AgentViews.Snapshot> cancel(@PathVariable long id,@RequestBody Cancel body) {
        return Result.success(app.cancel(UserContext.requireUserId(),id,body==null?null:body.turnId()));
    }
    @PostMapping("/conversations/{id}/workspace")
    public Result<AgentViews.Snapshot> workspace(@PathVariable long id,@RequestBody org.example.seedancegenarate.agent.application.AgentWorkspaceApplication.Command body) {
        long user=limited(); workspace.apply(user,id,body); return Result.success(app.snapshot(user,id));
    }
    @PostMapping("/conversations/{id}/approvals/{approvalId}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Result<AgentViews.Snapshot> approve(@PathVariable long id,@PathVariable String approvalId,
            @RequestBody org.example.seedancegenarate.agent.application.AgentApprovalApplication.Answer body) {
        long user=limited(); approvals.answer(user,id,approvalId,body); return Result.success(app.snapshot(user,id));
    }
    @GetMapping(value="/conversations/{id}/stream",produces=MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable long id,HttpServletRequest request,HttpServletResponse response) {
        response.setHeader("X-Accel-Buffering","no");
        response.setHeader("Cache-Control","no-cache");
        return streams.open(UserContext.requireUserId(),id,TokenUtils.resolveToken(request));
    }
    private long limited() {
        long user=UserContext.requireUserId();
        if(!rate.tryAcquireDistributed("agent:command:"+user,COMMANDS).allowed()) throw new BusinessException(429,"操作太频繁，请稍后再试");
        return user;
    }
}
