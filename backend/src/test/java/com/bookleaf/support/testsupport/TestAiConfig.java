package com.bookleaf.support.testsupport;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Replaces the real provider client with the controllable fake for every integration test. */
@TestConfiguration
public class TestAiConfig {

    @Bean
    @Primary
    public ControllableLlmClient controllableLlmClient() {
        return new ControllableLlmClient();
    }
}
