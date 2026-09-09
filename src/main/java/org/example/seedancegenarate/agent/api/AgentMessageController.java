package org.example.seedancegenarate.agent.api;

import lombok.RequiredArgsConstructor;
import org.example.seedancegenarate.agent.application.AgentApplication;
import org.example.seedancegenarate.config.RateLimitConfig;
import org.example.seedancegenarate.context.UserContext;
import org.example.seedancegenarate.entity.Result;
import org.example.seedancegenarate.exception.BusinessException;
import org.example.seedancegenarate.service.TokenBucketRateLimitService;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/** Message input owns its HTTP contract without changing DIRECT or approval errors. */
@RestController
@RequestMapping("/api/agent/conversations/{id}/messages")
@RequiredArgsConstructor
public class AgentMessageController {
    private final AgentApplication app;
    private final TokenBucketRateLimitService rate;
    private static final RateLimitConfig.Bucket COMMANDS=new RateLimitConfig.Bucket(true,20,10,60L);
    // Leave mapping media-neutral so converter errors reach this controller's 415 handler.
    @PostMapping
    public ResponseEntity<Result<AgentViews.Snapshot>> send(@PathVariable long id,@RequestBody AgentApplication.Send body) {
        Long user=UserContext.getUserId();if(user==null)throw BusinessException.unauthorized("请先登录");
        if(!rate.tryAcquireDistributed("agent:command:"+user,COMMANDS).allowed())throw new BusinessException(429,"操作太频繁，请稍后再试");
        return ResponseEntity.accepted().body(Result.success(app.send(user,id,body)));
    }
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<?>> rejected(BusinessException error) {
        int code=java.util.Set.of(400,401,403,404,409,413,415,422,429,503).contains(error.getCode())?error.getCode():500;
        return ResponseEntity.status(code).body(Result.fail(code,code==500?"消息提交失败，请稍后重试":error.getMessage()));
    }
    @ExceptionHandler({HttpMessageNotReadableException.class,MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Result<?>> malformed(){return ResponseEntity.badRequest().body(Result.fail(400,"消息格式或参数不合法"));}
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Result<?>> unsupported(){return ResponseEntity.status(415).body(Result.fail(415,"消息仅支持JSON，请先上传图片"));}
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Result<?>> tooLarge(){return ResponseEntity.status(413).body(Result.fail(413,"请求内容过大"));}
}
