package org.example.seedancegenarate.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record ApiKeyBudgetView(Long apiKeyId, BigDecimal limit, BigDecimal consumed,
                               BigDecimal reserved, BigDecimal remaining, String period,
                               OffsetDateTime resetAt, String currency, long version) {}
