package org.example.seedancegenarate.controller;

import org.example.seedancegenarate.entity.Result;
import org.example.seedancegenarate.entity.ShowcaseWork;
import org.example.seedancegenarate.entity.ShowcaseWork.*;
import org.example.seedancegenarate.service.ShowcaseService;
import org.springframework.http.*;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/admin/showcase")
public class AdminShowcaseController {
    private final ShowcaseService service;
    public AdminShowcaseController(ShowcaseService service) { this.service = service; }

    @GetMapping
    public Result<Page<ShowcaseWork>> page(@RequestParam(defaultValue = "1") long current,
                                         @RequestParam(defaultValue = "12") long size) {
        ShowcaseService.requireAdmin();
        return Result.success(service.adminPage(current, size));
    }
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<ShowcaseWork> create(@RequestParam String clientRequestId, @RequestParam String title,
                                      @RequestParam(required = false) String description,
                                      @RequestPart MultipartFile file, @RequestPart(required = false) MultipartFile cover) {
        ShowcaseService.requireAdmin();
        return Result.success(service.create(clientRequestId, title, description, file, cover));
    }
    @PatchMapping("/{id}")
    public Result<ShowcaseWork> edit(@PathVariable String id, @RequestBody Edit edit) {
        ShowcaseService.requireAdmin();
        return Result.success(service.edit(id, edit));
    }
    @GetMapping("/{id}/media")
    public ResponseEntity<Void> media(@PathVariable String id, @RequestParam(defaultValue = "false") boolean cover) {
        ShowcaseService.requireAdmin();
        return ResponseEntity.status(302).cacheControl(CacheControl.noStore()).location(service.adminMedia(id, cover)).build();
    }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> failure(Exception e) {
        int code = e instanceof ErrorResponse response ? response.getStatusCode().value() : 500;
        if (e instanceof org.springframework.http.converter.HttpMessageNotReadableException
                || e instanceof org.springframework.beans.TypeMismatchException
                || e instanceof org.springframework.validation.BindException) code = 400;
        String message = e instanceof ResponseStatusException response ? response.getReason()
                : code < 500 ? "请求参数无效" : "服务暂时不可用，请重试";
        return ResponseEntity.status(code).cacheControl(CacheControl.noStore()).body(Result.fail(code, message));
    }
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Result<Void>> rejected(ResponseStatusException e) {
        return failure(e);
    }
}
