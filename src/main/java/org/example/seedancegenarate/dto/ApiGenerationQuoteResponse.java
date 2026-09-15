package org.example.seedancegenarate.dto;

import java.math.BigDecimal;

/** 报价金额即真实提交时将冻结的金额。 */
public record ApiGenerationQuoteResponse(
        String provider,
        String model,
        Integer duration,
        String outputType,
        BigDecimal unitPrice,
        BigDecimal amount,
        String currency,
        String resolution,
        Double megapixels
) {
    public ApiGenerationQuoteResponse(String provider,String model,Integer duration,String outputType,
                                      BigDecimal unitPrice,BigDecimal amount,String currency) {
        this(provider,model,duration,outputType,unitPrice,amount,currency,null,null);
    }
}
