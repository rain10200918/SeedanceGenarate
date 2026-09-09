package org.example.seedancegenarate.agent.recipe;

import java.util.List;
import org.example.seedancegenarate.config.RateLimitConfig;
import org.example.seedancegenarate.context.UserContext;
import org.example.seedancegenarate.entity.Result;
import org.example.seedancegenarate.exception.BusinessException;
import org.example.seedancegenarate.service.TokenBucketRateLimitService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/agent/recipes")
public class RecipeController {
    private final RecipeCatalog catalog; private final TokenBucketRateLimitService rate;
    private static final RateLimitConfig.Bucket COMMANDS=new RateLimitConfig.Bucket(true,20,10,60L);
    public RecipeController(RecipeCatalog catalog,TokenBucketRateLimitService rate) {this.catalog=catalog;this.rate=rate;}
    @GetMapping public Result<List<RecipeCatalog.RecipeView>> list() {return Result.success(catalog.list(UserContext.requireUserId()));}
    @GetMapping("/templates") public Result<List<RecipeTemplates.Template>> templates() {return Result.success(catalog.templates(UserContext.requireUserId()));}
    @PostMapping("/templates/{id}/import") public Result<RecipeCatalog.RecipeView> importTemplate(@PathVariable String id,@RequestBody RecipeCatalog.ImportTemplate request) {return Result.success(catalog.importTemplate(limited(),id,request));}
    @GetMapping("/{id}") public Result<RecipeCatalog.RecipeView> get(@PathVariable String id) {return Result.success(catalog.get(UserContext.requireUserId(),id));}
    @PostMapping public Result<RecipeCatalog.RecipeView> save(@RequestBody RecipeCatalog.Save request) {return Result.success(catalog.save(limited(),request));}
    @PostMapping("/{id}/compile") public Result<RecipeCatalog.RecipeView> compile(@PathVariable String id,@RequestBody RecipeCatalog.Compile request) {return Result.success(catalog.compile(limited(),id,request));}
    @PostMapping("/{id}/publish") public Result<RecipeCatalog.RecipeView> publish(@PathVariable String id,@RequestBody RecipeCatalog.Publish request) {return Result.success(catalog.publish(limited(),id,request));}
    @PostMapping("/{id}/enabled") public Result<RecipeCatalog.RecipeView> enable(@PathVariable String id,@RequestBody RecipeCatalog.Enable request) {return Result.success(catalog.enable(limited(),id,request));}
    private long limited() {
        long user=UserContext.requireUserId();
        if(!rate.tryAcquireDistributed("agent:recipe:"+user,COMMANDS).allowed())throw new BusinessException(429,"操作太频繁，请稍后再试");
        return user;
    }
}
