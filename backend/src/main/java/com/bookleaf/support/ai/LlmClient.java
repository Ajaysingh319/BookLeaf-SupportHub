package com.bookleaf.support.ai;

/** A chat-completion style LLM provider. Called from the server only. */
public interface LlmClient {

    /** False when no API key is configured; callers then use the fallback without making a call. */
    boolean isConfigured();

    String provider();

    String model();

    /** Throws AiUnavailableException for rate limits, timeouts, provider errors and empty answers. */
    LlmResult complete(LlmRequest request);

    record LlmRequest(String system, String user, double temperature, int maxTokens, boolean jsonMode) {
    }

    record LlmResult(String content, Integer inputTokens, Integer outputTokens, long latencyMs) {
    }
}
