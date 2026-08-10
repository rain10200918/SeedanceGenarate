package org.example.seedancegenarate.task;

/** 登录 Token 已由 Redis TTL 自动清理，不再需要数据库清理任务。 */
public final class TokenCleanupTask {
    private TokenCleanupTask() {
    }
}
