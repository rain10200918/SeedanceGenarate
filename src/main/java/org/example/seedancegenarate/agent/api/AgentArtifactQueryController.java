package org.example.seedancegenarate.agent.api;

import lombok.RequiredArgsConstructor;
import org.example.seedancegenarate.agent.application.AgentArtifactQueryApplication;
import org.example.seedancegenarate.context.UserContext;
import org.example.seedancegenarate.entity.Result;
import org.example.seedancegenarate.exception.BusinessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestController
@RequestMapping("/api/agent/conversations/{id}/artifacts")
@RequiredArgsConstructor
public class AgentArtifactQueryController {
    private final AgentArtifactQueryApplication query;
    @GetMapping
    public Result<AgentViews.ArtifactPage> page(@PathVariable long id,
            @RequestParam(required=false) String beforeId,@RequestParam(defaultValue="20") int limit,
            @RequestParam(required=false) String artifactId) {
        return Result.success(query.page(authenticatedUser(),id,beforeId,limit,artifactId));
    }
    @GetMapping("/{artifactId}/versions/{version}")
    public Result<AgentViews.Artifact> version(@PathVariable long id,@PathVariable String artifactId,@PathVariable int version) {
        return Result.success(query.version(authenticatedUser(),id,artifactId,version));
    }
    @GetMapping("/{artifactId}/versions/{version}/media")
    public Result<AgentArtifactQueryApplication.Media> media(@PathVariable long id,@PathVariable String artifactId,@PathVariable int version,
                                                          jakarta.servlet.http.HttpServletResponse response) {
        response.setHeader("Cache-Control","no-store");
        return Result.success(query.media(authenticatedUser(),id,artifactId,version));
    }
    private long authenticatedUser() {
        Long user=UserContext.getUserId();
        if(user==null) throw BusinessException.unauthorized("请先登录");
        return user;
    }
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<?>> rejected(BusinessException error) {
        int code=java.util.Set.of(400,401,403,404).contains(error.getCode())?error.getCode():500;
        return ResponseEntity.status(code).body(Result.fail(code,code==500?"作品读取失败，请稍后重试":error.getMessage()));
    }
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Result<?>> malformed() {
        return ResponseEntity.badRequest().body(Result.fail(400,"作品查询参数不合法"));
    }
}
