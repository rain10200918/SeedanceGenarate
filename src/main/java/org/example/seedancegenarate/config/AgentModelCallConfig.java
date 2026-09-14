package org.example.seedancegenarate.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Background Agent calls have no browser request deadline; the bound is still finite. */
@Component
@ConfigurationProperties(prefix = "agent.model-call")
public class AgentModelCallConfig {
    private int maxInputTokens=24000;
    private int safetyTokens=1024;
    private int imageTokenReserve=4096;
    private java.util.Map<String,Integer> contextWindows=java.util.Map.of();
    public int getMaxInputTokens(){return maxInputTokens;}
    public void setMaxInputTokens(int value){if(value<2048||value>262144)throw new IllegalArgumentException("max-input-tokens must be within 2048..262144");maxInputTokens=value;}
    public int getSafetyTokens(){return safetyTokens;}
    public void setSafetyTokens(int value){if(value<128||value>16384)throw new IllegalArgumentException("safety-tokens must be within 128..16384");safetyTokens=value;}
    public int getImageTokenReserve(){return imageTokenReserve;}
    public void setImageTokenReserve(int value){if(value<256||value>32768)throw new IllegalArgumentException("image-token-reserve must be within 256..32768");imageTokenReserve=value;}
    public java.util.Map<String,Integer> getContextWindows(){return contextWindows;}
    public void setContextWindows(java.util.Map<String,Integer> values){
        if(values==null){contextWindows=java.util.Map.of();return;}
        if(values.size()>64||values.entrySet().stream().anyMatch(e->e.getKey()==null||e.getKey().isBlank()||e.getKey().length()>64||e.getValue()==null||e.getValue()<4096||e.getValue()>2097152))
            throw new IllegalArgumentException("context-windows requires channel names and windows within 4096..2097152");
        contextWindows=java.util.Map.copyOf(values);
    }
    // Legacy NULL database rows only; explicit channel capability always wins. Never infer from model names.
    private java.util.List<String> imageChannels = java.util.List.of();
    public java.util.List<String> getImageChannels() { return imageChannels; }
    public void setImageChannels(java.util.List<String> names) {
        if(names==null) { imageChannels=java.util.List.of();return; }
        if(names.size()>64 || names.stream().anyMatch(n->n==null || n.isBlank() || n.length()>64))
            throw new IllegalArgumentException("Agent image channel names must be nonempty and bounded");
        imageChannels=names.stream().map(String::trim).distinct().toList();
    }
    private int timeoutMs = 300000;
    private int plannerTokens = 4096;
    private int planTokens = 8192;
    private int scriptTokens = 8192;
    private int storyboardTokens = 12288;
    private int promptTokens = 4096;
    private int videoPromptTokens = 12288;
    private int videoPromptRepairTokens = 16384;
    private int videoPromptTimeoutMs = 420000;

    public int getPlannerTokens() {return plannerTokens;}
    public void setPlannerTokens(int value) {plannerTokens=bounded(value);}
    public int getPlanTokens() {return planTokens;}
    public void setPlanTokens(int value) {planTokens=bounded(value);}
    public int getScriptTokens() {return scriptTokens;}
    public void setScriptTokens(int value) {scriptTokens=bounded(value);}
    public int getStoryboardTokens() {return storyboardTokens;}
    public void setStoryboardTokens(int value) {storyboardTokens=bounded(value);}
    public int getPromptTokens() {return promptTokens;}
    public void setPromptTokens(int value) {promptTokens=bounded(value);}
    public int getVideoPromptTokens() {return videoPromptTokens;}
    public void setVideoPromptTokens(int value) {videoPromptTokens=bounded(value);}
    public int getVideoPromptRepairTokens() {return videoPromptRepairTokens;}
    public void setVideoPromptRepairTokens(int value) {videoPromptRepairTokens=bounded(value);}
    public int getVideoPromptTimeoutMs() {return videoPromptTimeoutMs;}
    public void setVideoPromptTimeoutMs(int value) {
        if(value<1000 || value>600000)throw new IllegalArgumentException("agent.model-call.video-prompt-timeout-ms must be within 1000..600000");
        videoPromptTimeoutMs=value;
    }

    private static int bounded(int value) {
        if(value<1024 || value>24576)throw new IllegalArgumentException("Agent output tokens must be within 1024..24576");
        return value;
    }

    /** Automatic growth is bounded; a user's higher channel limit is never reduced. */
    public int outputTokens(String scene,boolean repair,int channelTokens) {
        if("AGENT_VIDEO_PROMPT".equals(scene))
            return Math.max(channelTokens,repair?Math.max(videoPromptTokens,videoPromptRepairTokens):videoPromptTokens);
        int sceneTokens=switch(scene) {
            case "AGENT_PLAN" -> plannerTokens;
            case "AGENT_CREATIVE_PLAN" -> planTokens;
            case "AGENT_SCRIPT" -> scriptTokens;
            case "AGENT_STORYBOARD" -> storyboardTokens;
            case "AGENT_PROMPT" -> promptTokens;
            default -> channelTokens;
        };
        long initial=Math.max(channelTokens,sceneTokens);
        return (int)(repair?Math.max(initial,Math.min(24576L,initial*2L)):initial);
    }

    public int getTimeoutMs() { return timeoutMs; }

    public void setTimeoutMs(int timeoutMs) {
        if (timeoutMs < 1000 || timeoutMs > 600000) {
            throw new IllegalArgumentException("agent.model-call.timeout-ms must be within 1000..600000");
        }
        this.timeoutMs = timeoutMs;
    }
}
