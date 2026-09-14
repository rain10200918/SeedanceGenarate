package org.example.seedancegenarate.exception;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.PathContainer;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.handler.AbstractHandlerExceptionResolver;
import org.springframework.web.util.ServletRequestPathUtils;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** 按请求路径接管，包含拦截器异常及handler尚不存在的路由错误。 */
public final class ApiV1ExceptionResolver extends AbstractHandlerExceptionResolver {
    private static final PathPattern PATH = PathPatternParser.defaultInstance.parse("/api/v1/**");
    private final ObjectMapper objectMapper;

    public ApiV1ExceptionResolver(ObjectMapper objectMapper) { this.objectMapper = objectMapper; }

    @Override
    protected ModelAndView doResolveException(HttpServletRequest request, HttpServletResponse response,
                                              Object handler, Exception failure) {
        PathContainer path = ServletRequestPathUtils.hasParsedRequestPath(request)
                ? ServletRequestPathUtils.getParsedRequestPath(request).pathWithinApplication()
                : ServletRequestPathUtils.parseAndCache(request).pathWithinApplication();
        if (!PATH.matches(path)) return null;
        if (response.isCommitted()) return new ModelAndView();
        if (failure instanceof org.apache.catalina.connector.ClientAbortException
                || failure instanceof org.springframework.web.context.request.async.AsyncRequestNotUsableException) {
            return new ModelAndView();
        }
        ApiException api = ApiFailureClassifier.classify(failure);
        try {
            // Serialize before touching the response; never fall through into a second error writer.
            String body = objectMapper.writeValueAsString(new ApiErrorResponse(new ApiErrorResponse.ApiError(
                    api.getCode(), api.getMessage(), ApiExceptionHandler.requestId(request))));
            if (response.isCommitted()) return new ModelAndView();
            response.resetBuffer();
            response.setStatus(api.getHttpStatus().value());
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setContentType("application/json");
            response.setHeader("Content-Length", null);
            response.setHeader("Content-Disposition", null);
            if (api.getHttpStatus() == HttpStatus.TOO_MANY_REQUESTS) response.setHeader("Retry-After", "30");
            if (failure instanceof HttpRequestMethodNotSupportedException method && method.getSupportedMethods() != null) {
                response.setHeader("Allow", String.join(", ", method.getSupportedMethods()));
            }
            try {
                response.getWriter().write(body);
            } catch (IllegalStateException outputStreamAlreadySelected) {
                response.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException | IllegalStateException writeFailure) {
            // A disconnected/committed response cannot safely receive another JSON envelope.
            logger.debug("API error response could not be written");
        }
        return new ModelAndView();
    }
}
