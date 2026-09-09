package org.example.seedancegenarate.agent.model;

import org.example.seedancegenarate.exception.BusinessException;

/** Safe message; raw response is separately available only to opt-in development diagnostics. */
public class InvalidAgentDecisionException extends BusinessException {
    private final String rawOutput;
    public InvalidAgentDecisionException(String detail) { this(detail,null); }
    public InvalidAgentDecisionException(String detail,String rawOutput) { super(502, detail);this.rawOutput=rawOutput; }
    @com.fasterxml.jackson.annotation.JsonIgnore
    public String rawOutput() { return rawOutput; }
}
