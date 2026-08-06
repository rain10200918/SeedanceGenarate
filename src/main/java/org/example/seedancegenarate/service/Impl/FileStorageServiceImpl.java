package org.example.seedancegenarate.service.Impl;

import lombok.RequiredArgsConstructor;
import org.example.seedancegenarate.config.FileConfig;
import org.example.seedancegenarate.service.FileStorageService;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class FileStorageServiceImpl implements FileStorageService {
    private final FileConfig fileConfig;

    @Override
    public List<String> saveFiles(MultipartFile[] files) throws IOException {
        // 图片保存目录
        Path uploadDir =
                Paths.get(
                        fileConfig.getUploadPath()
                );

        // 不存在则创建
        if (!Files.exists(uploadDir)) {
            Files.createDirectories(uploadDir);
        }
        List<String> paths = new ArrayList<>();
        for (MultipartFile file : files) {
            if(file.isEmpty()){
                continue;
            }
            // 获取后缀
            String originalName = file.getOriginalFilename();
            String suffix = "";
            if(originalName != null && originalName.contains(".")){
                suffix = originalName.substring(originalName.lastIndexOf("."));
            }
            // UUID文件名
            String filename = UUID.randomUUID() + suffix;
            Path target = uploadDir.resolve(filename);
            // 保存
            Files.copy(
                    file.getInputStream(),
                    target,
                    StandardCopyOption.REPLACE_EXISTING
            );
            paths.add(target.toString());
        }
        return paths;
    }
}
