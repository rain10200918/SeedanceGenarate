package org.example.seedancegenarate.agent.recipe;

import lombok.RequiredArgsConstructor;
import org.example.seedancegenarate.agent.api.AgentViews;
import org.example.seedancegenarate.agent.application.AgentApplication;
import org.example.seedancegenarate.config.RateLimitConfig;
import org.example.seedancegenarate.context.UserContext;
import org.example.seedancegenarate.entity.Result;
import org.example.seedancegenarate.exception.BusinessException;
import org.example.seedancegenarate.service.TokenBucketRateLimitService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/agent/conversations/{id}/recipe")
@RequiredArgsConstructor
public class AgentRecipeController {
    private final AgentRecipeApplication recipes;
    private final AgentApplication agent;
    private final TokenBucketRateLimitService rate;
    private static final RateLimitConfig.Bucket COMMANDS=new RateLimitConfig.Bucket(true,20,10,60L);
    @PostMapping("/start")
    public Result<AgentViews.Snapshot> start(@PathVariable long id,@RequestBody AgentRecipeApplication.Start request){long user=limited();recipes.start(user,id,request);return Result.success(agent.snapshot(user,id));}
    @PostMapping("/runs/{runId}/commands")
    public Result<AgentViews.Snapshot> command(@PathVariable long id,@PathVariable String runId,@RequestBody AgentRecipeApplication.Command request){long user=limited();recipes.command(user,id,runId,request);return Result.success(agent.snapshot(user,id));}
    private long limited(){long user=UserContext.requireUserId();if(!rate.tryAcquireDistributed("agent:command:"+user,COMMANDS).allowed())throw new BusinessException(429,"操作太频繁，请稍后重试");return user;}
}
