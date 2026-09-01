package org.example.seedancegenarate.dto;

import lombok.Data;

@Data
public class AuthRequest {
    private String username;
    private String password;
    private String inviteCode;
    private String captchaProof;
}
