package org.example.seedancegenarate.agent.runtime;

import lombok.RequiredArgsConstructor;
import org.example.seedancegenarate.entity.AsyncJob;
import org.example.seedancegenarate.task.AsyncJobHandler;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AgentGenerationHandler implements AsyncJobHandler {
    private final AgentGenerationRuntime runtime;
    public String jobType() { return AgentGenerationRuntime.JOB; }
    public long leaseSeconds() { return AgentRuntime.LEASE_SECONDS; }
    public String concurrencyGroup() { return "agent-text"; }
    public int concurrencyLimit() { return 2; }
    public void execute(AsyncJob lease) { runtime.execute(lease); }
}
