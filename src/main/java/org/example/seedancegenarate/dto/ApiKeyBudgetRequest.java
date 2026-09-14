package org.example.seedancegenarate.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

public record ApiKeyBudgetRequest(
        @JsonProperty(required = true) BigDecimal limit,
        @JsonProperty(required = true) Long expectedVersion) {
    public ApiKeyBudgetRequest {
        if (expectedVersion == null || expectedVersion < 0) {
            throw new IllegalArgumentException("请提供有效的预算版本");
        }
    }
}
