package org.example.seedancegenarate.agent.api;

import lombok.RequiredArgsConstructor;
import org.example.seedancegenarate.agent.application.AgentImageInputs;
import org.example.seedancegenarate.context.UserContext;
import org.example.seedancegenarate.entity.Result;
import org.example.seedancegenarate.exception.BusinessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

@RestController
@RequestMapping("/api/agent/images")
@RequiredArgsConstructor
public class AgentImageController {
    private final AgentImageInputs images;
    @PostMapping(consumes="multipart/form-data")
    public Result<AgentImageInputs.ImageRef> upload(@RequestParam("file") MultipartFile file) {
        Long user=UserContext.getUserId();if(user==null)throw BusinessException.unauthorized("请先登录");
        return Result.success(images.upload(user,file));
    }
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<?>> rejected(BusinessException error) {
        int code=java.util.Set.of(400,401,403,503).contains(error.getCode())?error.getCode():500;
        return ResponseEntity.status(code).body(Result.fail(code,code==500?"图片上传失败，请稍后重试":error.getMessage()));
    }
    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<Result<?>> missingFile(){return ResponseEntity.badRequest().body(Result.fail(400,"请选择图片"));}
}
