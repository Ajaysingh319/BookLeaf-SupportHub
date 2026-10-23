package com.bookleaf.support.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * One AI call attempt (TDD AIEvent): task, provider, model, outcome, latency and token counts.
 * It exists for debugging and cost tracking, and never stores prompts, answers or provider errors.
 */
@Entity
@Table(name = "ai_events", indexes = {
        @Index(name = "idx_ai_events_ticket", columnList = "ticketId"),
        @Index(name = "idx_ai_events_created", columnList = "createdAt")
})
public class AiEvent {

    public enum Task { TRIAGE, DRAFT }

    public enum Outcome { SUCCESS, FALLBACK, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Plain id (no foreign key) so the log survives ticket deletion. */
    @Column(nullable = false)
    private UUID ticketId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Task taskType;

    private String provider;

    private String model;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Outcome outcome;

    /** Short code such as rate_limited or not_configured; never a raw provider message. */
    private String failureReason;

    private Long latencyMs;

    private Integer inputTokens;

    private Integer outputTokens;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public AiEvent() {
    }

    public AiEvent(UUID ticketId, Task taskType, String provider, String model, Outcome outcome,
                   String failureReason, Long latencyMs, Integer inputTokens, Integer outputTokens) {
        this.ticketId = ticketId;
        this.taskType = taskType;
        this.provider = provider;
        this.model = model;
        this.outcome = outcome;
        this.failureReason = failureReason;
        this.latencyMs = latencyMs;
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
    }

    public UUID getId() { return id; }
    public UUID getTicketId() { return ticketId; }
    public Task getTaskType() { return taskType; }
    public String getProvider() { return provider; }
    public String getModel() { return model; }
    public Outcome getOutcome() { return outcome; }
    public String getFailureReason() { return failureReason; }
    public Long getLatencyMs() { return latencyMs; }
    public Integer getInputTokens() { return inputTokens; }
    public Integer getOutputTokens() { return outputTokens; }
    public Instant getCreatedAt() { return createdAt; }
}
