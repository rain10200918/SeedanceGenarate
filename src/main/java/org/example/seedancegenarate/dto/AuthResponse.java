package org.example.seedancegenarate.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import org.example.seedancegenarate.entity.AppUser;

@Data
@AllArgsConstructor
public class AuthResponse {
    private String token;
    private AppUser user;
}
