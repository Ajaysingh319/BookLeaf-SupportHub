package com.bookleaf.support;

import com.bookleaf.support.config.AppProperties;

/** Builds configuration objects for unit tests, so tests never depend on the real environment. */
public final class TestProperties {

    public static final String SECRET = "unit-test-secret-that-is-at-least-32-chars-long";

    private TestProperties() {
    }

    public static AppProperties create() {
        return withAuth(SECRET, 60);
    }

    public static AppProperties withAuth(String secret, long ttlMinutes) {
        return build(secret, ttlMinutes, ai(null, "http://llm.test/v1", null, 2, 0, 20));
    }

    /** Provider settings for tests of the real HTTP client, pointed at a local fake server. */
    public static AppProperties withLlm(String apiKey, String baseUrl, String reasoningEffort,
                                        int maxAttempts, long retryDelayMs, int readTimeoutSeconds) {
        return build(SECRET, 60, ai(apiKey, baseUrl, reasoningEffort, maxAttempts, retryDelayMs, readTimeoutSeconds));
    }

    private static AppProperties.Ai ai(String apiKey, String baseUrl, String reasoningEffort,
                                       int maxAttempts, long retryDelayMs, int readTimeoutSeconds) {
        return new AppProperties.Ai(
                apiKey, "test-provider", "test-model", baseUrl, reasoningEffort,
                "classpath:ai/", "classpath:ai/fallback-triage.properties", 5, readTimeoutSeconds,
                maxAttempts, retryDelayMs,
                new AppProperties.Triage(0.0, 512, 3000, 300),
                new AppProperties.Draft(0.3, 1024, 4000, 600, 4000, 3));
    }

    private static AppProperties build(String secret, long ttlMinutes, AppProperties.Ai ai) {
        return new AppProperties(
                new AppProperties.Auth(secret, ttlMinutes),
                new AppProperties.Cors("http://frontend.test"),
                new AppProperties.Pagination(20, 50),
                ai,
                new AppProperties.Seed(false, "classpath:seed/bookleaf_sample_data.json", null, null, null, null),
                new AppProperties.Ui("Test Portal", "USD", "en-US", 5000, 10000, 45, 1000, "In Production", 5, 48));
    }
}
