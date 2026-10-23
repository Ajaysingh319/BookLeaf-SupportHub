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
        return new AppProperties(
                new AppProperties.Auth(secret, ttlMinutes),
                new AppProperties.Cors("http://frontend.test"),
                new AppProperties.Pagination(20, 50),
                new AppProperties.Ai(
                        null, "test-provider", "test-model", "http://llm.test/v1",
                        "classpath:ai/", "classpath:ai/fallback-triage.properties", 5, 20,
                        new AppProperties.Triage(0.0, 150, 3000, 300),
                        new AppProperties.Draft(0.3, 600, 4000, 600, 4000, 3)),
                new AppProperties.Seed(false, "classpath:seed/bookleaf_sample_data.json", null, null, null, null),
                new AppProperties.Ui("Test Portal", "USD", "en-US", 5000, 10000, 45, 1000, "In Production", 5, 48));
    }
}
