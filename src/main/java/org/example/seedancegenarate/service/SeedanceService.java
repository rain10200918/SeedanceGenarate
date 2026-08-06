package org.example.seedancegenarate.service;

import com.baomidou.mybatisplus.extension.service.IService;
import org.example.seedancegenarate.entity.VideoTask;

import java.util.List;

public interface SeedanceService  {
    String generate(
            List<String> imagePaths,
            String prompt,
            Integer duration,
            String ratio,
            String model
    ) throws Exception;

    Object getTask(
            String taskId
    ) throws Exception;
}
