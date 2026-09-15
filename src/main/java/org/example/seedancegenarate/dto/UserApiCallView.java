package org.example.seedancegenarate.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import com.fasterxml.jackson.annotation.JsonFormat;

public record UserApiCallView(Long id, String requestId, String taskId, Long apiKeyId,
                              String apiKeyName, String keyPrefix, String model, String provider,
                              String status, Integer httpCode, String errorCode, BigDecimal costAmount,
                              String currency,
                              @JsonFormat(shape = JsonFormat.Shape.STRING) LocalDateTime createTime,
                              @JsonFormat(shape = JsonFormat.Shape.STRING) LocalDateTime updateTime,
                              Long queuedMs, Long generateMs, Long totalMs) {
}
