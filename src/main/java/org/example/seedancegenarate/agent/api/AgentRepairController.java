package org.example.seedancegenarate.agent.api;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.example.seedancegenarate.agent.application.*;
import org.example.seedancegenarate.config.RateLimitConfig;
import org.example.seedancegenarate.context.UserContext;
import org.example.seedancegenarate.entity.Result;
import org.example.seedancegenarate.exception.BusinessException;
import org.example.seedancegenarate.service.TokenBucketRateLimitService;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestController @RequiredArgsConstructor
@RequestMapping("/api/agent/conversations")
public class AgentRepairController {
    private final AgentRepairApplication repairs;
    private final AgentApplication app;
    private final TokenBucketRateLimitService rate;
    private static final RateLimitConfig.Bucket COMMANDS=new RateLimitConfig.Bucket(true,20,10,60L);
    private long owner(long id) {
        Long user=UserContext.getUserId();if(user==null)throw BusinessException.unauthorized("请先登录");
        if(id<1)throw BusinessException.badRequest("会话编号无效");return user;
    }
    @GetMapping("/{id}/repairs")
    public Result<JsonNode> suggestions(@PathVariable long id) {return Result.success(repairs.suggestions(owner(id),id));}
    @PostMapping("/{id}/repairs")
    public Result<AgentViews.Snapshot> confirm(@PathVariable long id,@RequestBody AgentRepairApplication.Command command) {
        long user=owner(id);
        if(!rate.tryAcquireDistributed("agent:command:"+user,COMMANDS).allowed())throw new BusinessException(429,"操作太频繁，请稍后再试");
        repairs.confirm(user,id,command);return Result.success(app.snapshot(user,id));
    }
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<?>> rejected(BusinessException error) {
        int code=java.util.Set.of(400,401,403,404,409,429).contains(error.getCode())?error.getCode():500;
        return ResponseEntity.status(code).body(Result.fail(code,code==500?"修复请求暂未确认，请重试原请求":error.getMessage()));
    }
    @ExceptionHandler({HttpMessageNotReadableException.class,MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Result<?>> malformed() {return ResponseEntity.badRequest().body(Result.fail(400,"修复请求格式无效"));}
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<?>> unavailable() {return ResponseEntity.internalServerError().body(Result.fail(500,"修复请求暂未确认，请重试原请求"));}
}
