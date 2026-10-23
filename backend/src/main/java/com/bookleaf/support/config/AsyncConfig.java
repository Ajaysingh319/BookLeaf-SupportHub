package com.bookleaf.support.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/** Enables @Async for background AI triage. Pool size is set in application.properties. */
@Configuration
@EnableAsync
public class AsyncConfig {
}
