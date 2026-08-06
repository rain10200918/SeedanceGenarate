package org.example.seedancegenarate.service.Impl;

import org.example.seedancegenarate.service.ApiDocService;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Service
public class ApiDocServiceImpl implements ApiDocService {

    private static final String DOC_PATH = "api-docs.md";

    @Override
    public String content() {
        try {
            return new ClassPathResource(DOC_PATH).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }
}
