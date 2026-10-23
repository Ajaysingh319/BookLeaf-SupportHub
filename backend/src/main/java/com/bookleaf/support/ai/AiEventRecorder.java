package com.bookleaf.support.ai;

import com.bookleaf.support.domain.AiEvent;
import com.bookleaf.support.repository.AiEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Writes AI call records. A logging failure must never break ticket handling, so it is swallowed. */
@Component
public class AiEventRecorder {

    private static final Logger log = LoggerFactory.getLogger(AiEventRecorder.class);

    private final AiEventRepository events;
    private final LlmClient llm;

    public AiEventRecorder(AiEventRepository events, LlmClient llm) {
        this.events = events;
        this.llm = llm;
    }

    public void record(UUID ticketId, AiEvent.Task task, AiEvent.Outcome outcome, String failureReason,
                       LlmClient.LlmResult result) {
        try {
            events.save(new AiEvent(
                    ticketId, task, llm.provider(), llm.model(), outcome, failureReason,
                    result == null ? null : result.latencyMs(),
                    result == null ? null : result.inputTokens(),
                    result == null ? null : result.outputTokens()));
        } catch (RuntimeException ex) {
            log.warn("Could not record AI event for ticket {}", ticketId, ex);
        }
    }
}
