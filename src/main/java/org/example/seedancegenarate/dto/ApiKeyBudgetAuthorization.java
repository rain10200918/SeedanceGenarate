package org.example.seedancegenarate.dto;

import java.math.BigDecimal;

/** Internal compensation cursor; never an authorization to resubmit or refund by age. */
public record ApiKeyBudgetAuthorization(long apiKeyId, long userId, long taskId,
                                        String period, BigDecimal amount) {}
