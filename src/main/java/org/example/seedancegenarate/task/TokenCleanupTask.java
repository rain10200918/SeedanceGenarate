package org.example.seedancegenarate.task;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.seedancegenarate.service.UserTokenService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class TokenCleanupTask {
    private final UserTokenService userTokenService;

    @Scheduled(fixedDelay = 60 * 60 * 1000L, initialDelay = 60 * 1000L)
    public void cleanupExpiredTokens() {
        int count = userTokenService.deleteExpiredTokens();
        if (count > 0) {
            log.info("已清理过期 token：{} 个", count);
        }
    }
}
