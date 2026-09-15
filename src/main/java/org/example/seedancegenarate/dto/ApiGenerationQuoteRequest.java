package org.example.seedancegenarate.dto;

/** POST /api/v1/generations/quote 的稳定对外请求。 */
public record ApiGenerationQuoteRequest(String model, Integer duration, String resolution, Double megapixels) {
    public ApiGenerationQuoteRequest(String model,Integer duration) { this(model,duration,null,null); }
}
