package org.example.seedancegenarate.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.seedancegenarate.exception.ApiV1ExceptionResolver;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/** 保留全部默认MVC/UI处理器，仅把v1路径解析器放到前面。 */
@Configuration(proxyBeanMethods = false)
public class ApiV1ExceptionConfig implements WebMvcConfigurer {
    private final ObjectMapper objectMapper;

    public ApiV1ExceptionConfig(ObjectMapper objectMapper) { this.objectMapper = objectMapper; }

    @Override
    public void extendHandlerExceptionResolvers(List<HandlerExceptionResolver> resolvers) {
        resolvers.add(0, new ApiV1ExceptionResolver(objectMapper));
    }
}
