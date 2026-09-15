package org.example.seedancegenarate.dto;

/** Raw query values are parsed strictly by the service, including ISO local timestamps. */
public record UserApiCallQuery(Long apiKeyId, String model, String provider, String status,
                               String errorCode, String from, String to) {
}
