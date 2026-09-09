package org.example.seedancegenarate.agent.persistence;

import java.time.LocalDateTime;

/** Persistence snapshots, never identities supplied by the model. */
public final class AgentRows {
    private AgentRows() {}
    public record Session(String id, long conversationId, long userId, long revision,
                          String goal, String summary, String activeTurnId) {}
    public record Turn(String id, String sessionId, String channel, String status,
                       int step, long epoch, String error, LocalDateTime deadline) {}
    public record Call(String id, String turnId, String skillId, String skillVersion,
                       String input, String status, long epoch, int step) {}
    public record Interaction(String id, String turnId, long epoch, int version,
                              String status, String question, String options, LocalDateTime expires) {}
    public record Approval(String id,String sessionId,String turnId,String callId,long epoch,int step,int version,
                           String status,String quote,String requestId,String taskId,String error,LocalDateTime expires) {}
}
