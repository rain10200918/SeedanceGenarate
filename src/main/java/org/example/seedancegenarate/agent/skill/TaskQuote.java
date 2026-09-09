package org.example.seedancegenarate.agent.skill;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;

/** Immutable server-produced approval payload; clients never supply this record. */
public record TaskQuote(String provider,String modelId,String modelLabel,String mediaType,
                        JsonNode inputSnapshot,BigDecimal amount,String currency,String origin) {
    public TaskQuote {
        origin = origin == null ? "AGENT" : origin;
        if (!java.util.Set.of("AGENT", "DIRECT").contains(origin)) throw new IllegalArgumentException("Invalid quote origin");
        inputSnapshot = inputSnapshot == null ? null : inputSnapshot.deepCopy();
    }
    public TaskQuote(String provider,String modelId,String modelLabel,String mediaType,
                     JsonNode inputSnapshot,BigDecimal amount,String currency) {
        this(provider,modelId,modelLabel,mediaType,inputSnapshot,amount,currency,"AGENT");
    }
}
