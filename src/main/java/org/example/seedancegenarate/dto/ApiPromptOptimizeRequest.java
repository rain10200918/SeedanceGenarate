package org.example.seedancegenarate.dto;

public record ApiPromptOptimizeRequest(
        String prompt,
        String model,
        Integer imageCount,
        Integer videoCount,
        Integer audioCount,
        Integer duration,
        String ratio
) {
}
