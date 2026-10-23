package com.bookleaf.support.testsupport;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Test replacements for the two things that cannot run for real in tests: the AI provider (a controllable
 * fake) and the clock (real time plus an offset tests can advance). The mutable clock is itself a
 * java.time.Clock, so it replaces the application's clock everywhere.
 */
@TestConfiguration
public class TestAiConfig {

    @Bean
    @Primary
    public ControllableLlmClient controllableLlmClient() {
        return new ControllableLlmClient();
    }

    @Bean
    @Primary
    public MutableClock mutableClock() {
        return new MutableClock();
    }
}
