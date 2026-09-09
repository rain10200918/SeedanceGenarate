package org.example.seedancegenarate.agent.skill;

import org.example.seedancegenarate.exception.BusinessException;

/** Model document failure only; never use for input authorization or execution failures. */
public final class SkillOutputContractException extends BusinessException {
    public static final String CODE = "SKILL_OUTPUT_INVALID";
    public SkillOutputContractException(String detail) { super(502, detail); }
    public SkillOutputContractException(String detail, Throwable cause) { super(502, detail, cause); }
}
