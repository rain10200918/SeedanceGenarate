package org.example.seedancegenarate.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.seedancegenarate.context.UserContext;
import org.example.seedancegenarate.dto.*;
import org.example.seedancegenarate.entity.Result;
import org.example.seedancegenarate.exception.BusinessException;
import org.example.seedancegenarate.service.UserApiCallService;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/user/api-calls")
@RequiredArgsConstructor
@Slf4j
public class UserApiCallController {
    private final UserApiCallService service;

    @GetMapping
    public Result<Page<UserApiCallView>> page(
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "20") long size,
            @ModelAttribute UserApiCallQuery query) {
        return Result.success(service.page(owner(), current, size, query));
    }

    @GetMapping("/summary")
    public Result<UserApiCallSummary> summary(@ModelAttribute UserApiCallQuery query) {
        return Result.success(service.summary(owner(), query));
    }

    private long owner() {
        Long owner = UserContext.getUserId();
        if (owner == null) throw BusinessException.unauthorized("请先登录");
        return owner;
    }

    @ExceptionHandler({BusinessException.class, BindException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Result<?>> handleInvalidRequest(Exception failure) {
        int code = failure instanceof BusinessException business ? business.getCode() : 400;
        String message = code == 401 ? "请先登录" : code == 400 ? "请求参数不合法" : "请求失败";
        return ResponseEntity.status(code).body(Result.fail(code, message));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<?>> handleUnexpectedFailure(Exception failure) {
        log.error("用户API调用查询失败", failure);
        return ResponseEntity.internalServerError().body(Result.fail(500, "系统繁忙，请稍后重试"));
    }
}
