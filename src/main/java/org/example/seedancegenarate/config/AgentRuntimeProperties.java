package org.example.seedancegenarate.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Independent bounds for one Turn slice, one durable Plan, and human resumes. */
@Component
@ConfigurationProperties(prefix = "agent.runtime")
public class AgentRuntimeProperties {
    private int maxDecisionsPerTurn = 16;
    private int maxPlanSteps = 100;
    private int maxHumanResumes = 8;
    private boolean batchEnabled = true;
    private int batchParallelLimit = 2;
    private java.util.List<String> videoModelPriority = java.util.List.of();
    public java.util.List<String> getVideoModelPriority(){return videoModelPriority;}
    public void setVideoModelPriority(java.util.List<String> value){
        if(value==null){videoModelPriority=java.util.List.of();return;}
        if(value.size()>100||value.stream().anyMatch(v->v==null||v.isBlank()||v.length()>128)||new java.util.HashSet<>(value).size()!=value.size())
            throw new IllegalArgumentException("agent.runtime.video-model-priority requires unique nonblank model IDs (at most 100)");
        videoModelPriority=java.util.List.copyOf(value);
    }
    public boolean isBatchEnabled(){return batchEnabled;}
    public void setBatchEnabled(boolean value){batchEnabled=value;}
    public int getBatchParallelLimit(){return batchParallelLimit;}
    public void setBatchParallelLimit(int value){if(value<1||value>4)throw new IllegalArgumentException("agent.runtime.batch-parallel-limit must be 1..4");batchParallelLimit=value;}

    public int getMaxDecisionsPerTurn() { return maxDecisionsPerTurn; }
    public int getMaxPlanSteps() { return maxPlanSteps; }
    public int getMaxHumanResumes() { return maxHumanResumes; }

    public void setMaxDecisionsPerTurn(int value) {
        maxDecisionsPerTurn = positive(value,"agent.runtime.max-decisions-per-turn");
    }
    public void setMaxPlanSteps(int value) {
        maxPlanSteps = positive(value,"agent.runtime.max-plan-steps");
    }
    public void setMaxHumanResumes(int value) {
        maxHumanResumes = positive(value,"agent.runtime.max-human-resumes");
    }
    private static int positive(int value,String name) {
        if(value<1) throw new IllegalArgumentException(name+" must be positive");
        return value;
    }
}
