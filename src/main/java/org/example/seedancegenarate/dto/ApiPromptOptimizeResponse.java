package org.example.seedancegenarate.dto;

public record ApiPromptOptimizeResponse(
        String originalPrompt,
        String optimizedPrompt,
        String model
) {
}
