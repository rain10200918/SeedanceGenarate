package org.example.seedancegenarate.controller;

import lombok.RequiredArgsConstructor;
import org.example.seedancegenarate.context.UserContext;
import org.example.seedancegenarate.dto.NodeHealth;
import org.example.seedancegenarate.entity.Result;
import org.example.seedancegenarate.service.NodeHealthService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 管理端 ComfyUI 运维：节点健康检测（实时探测全部启用节点） */
@RestController
@RequestMapping("/api/admin/comfyui")
@RequiredArgsConstructor
public class AdminComfyUiController {

    private final NodeHealthService nodeHealthService;

    @GetMapping("/nodes")
    public Result<List<NodeHealth>> nodes() {
        requireAdmin();
        return Result.success(nodeHealthService.checkAll());
    }

    private void requireAdmin() {
        if (!UserContext.isAdmin()) {
            throw new RuntimeException("无权限访问");
        }
    }
}
