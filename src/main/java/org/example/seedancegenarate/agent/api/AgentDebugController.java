package org.example.seedancegenarate.agent.api;

import lombok.RequiredArgsConstructor;
import org.example.seedancegenarate.agent.application.AgentDebugQuery;
import org.example.seedancegenarate.context.UserContext;
import org.example.seedancegenarate.entity.Result;
import org.example.seedancegenarate.exception.BusinessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/agent/conversations")
@RequiredArgsConstructor
public class AgentDebugController {
    private final AgentDebugQuery query;

    @GetMapping("/{id}/diagnostics")
    public Result<AgentDebugQuery.Diagnostic> read(@PathVariable String id,
            @RequestParam(defaultValue="20") String limit) {
        if (UserContext.getUserId() == null) throw BusinessException.unauthorized("请先登录");
        if (!UserContext.isAdmin()) throw BusinessException.forbidden("仅管理员可查看诊断");
        return Result.success(query.read(positive(id,19), (int) positive(limit,2)));
    }

    private static long positive(String value,int length) {
        if(value == null || value.length()>length || !value.matches("[1-9][0-9]*"))
            throw BusinessException.badRequest("诊断查询参数不合法");
        try { return Long.parseLong(value); }
        catch(NumberFormatException e) { throw BusinessException.badRequest("诊断查询参数不合法"); }
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<?>> rejected(BusinessException e) {
        int code=java.util.Set.of(400,401,403,404).contains(e.getCode())?e.getCode():500;
        return ResponseEntity.status(code).body(Result.fail(code,code==500?"诊断读取失败，请稍后重试":e.getMessage()));
    }
    @ExceptionHandler(org.springframework.dao.DataAccessException.class)
    public ResponseEntity<Result<?>> unavailable() {
        return ResponseEntity.internalServerError().body(Result.fail(500,"诊断读取失败，请稍后重试"));
    }
}
