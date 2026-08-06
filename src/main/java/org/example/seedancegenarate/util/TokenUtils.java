package org.example.seedancegenarate.util;

import jakarta.servlet.http.HttpServletRequest;

public class TokenUtils {
    private TokenUtils() {
    }

    public static String resolveToken(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.startsWith("Bearer ")) {
            return authorization.substring(7);
        }
        String token = request.getHeader("X-Token");
        if (token != null && !token.isBlank()) {
            return token;
        }
        return request.getParameter("token");
    }
}
