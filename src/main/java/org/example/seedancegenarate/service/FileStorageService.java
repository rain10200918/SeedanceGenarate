package org.example.seedancegenarate.service;

import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

public interface FileStorageService {
    public List<String> saveFiles(MultipartFile[] files) throws IOException;
}
