package com.bookleaf.support.ai;

import com.bookleaf.support.config.AppProperties;
import com.bookleaf.support.domain.AiEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

/**
 * Classifies a ticket and suggests a priority. The model is called once per ticket, with only the
 * ticket text and the booking status. Any failure falls back to rule-based triage so a ticket
 * always ends up with a usable category and priority.
 */
@Service
public class AiTriageService {

    private static final Logger log = LoggerFactory.getLogger(AiTriageService.class);

    private final AiTicketContext context;
    private final LlmClient llm;
    private final TriageParser parser;
    private final FallbackTriage fallback;
    private final PromptTemplates prompts;
    private final AiEventRecorder events;
    private final AppProperties.Triage settings;

    public AiTriageService(AiTicketContext context, LlmClient llm, TriageParser parser, FallbackTriage fallback,
                           PromptTemplates prompts, AiEventRecorder events, AppProperties properties) {
        this.context = context;
        this.llm = llm;
        this.parser = parser;
        this.fallback = fallback;
        this.prompts = prompts;
        this.events = events;
        this.settings = properties.ai().triage();
    }

    public void triage(UUID ticketId) {
        Optional<PromptTicket> loaded = context.load(ticketId);
        if (loaded.isEmpty()) {
            return;
        }
        PromptTicket ticket = loaded.get();

        try {
            LlmClient.LlmResult response = llm.complete(new LlmClient.LlmRequest(
                    prompts.triageSystem(), prompts.triageUser(ticket),
                    settings.temperature(), settings.maxTokens(), true));
            try {
                TriageResult result = parser.parse(response.content());
                context.applyTriage(ticketId, result, true);
                events.record(ticketId, AiEvent.Task.TRIAGE, AiEvent.Outcome.SUCCESS, null, response);
            } catch (AiUnavailableException ex) {
                // The call worked (and cost tokens) but the answer was unusable.
                useFallback(ticketId, ticket, ex.reasonCode(), response);
            }
        } catch (AiUnavailableException ex) {
            useFallback(ticketId, ticket, ex.reasonCode(), null);
        }
    }

    private void useFallback(UUID ticketId, PromptTicket ticket, String reasonCode, LlmClient.LlmResult response) {
        log.info("Triage for ticket {} used the rule-based fallback ({})", ticketId, reasonCode);
        TriageResult result = fallback.classify(ticket.subject(), ticket.description());
        context.applyTriage(ticketId, result, false);
        events.record(ticketId, AiEvent.Task.TRIAGE, AiEvent.Outcome.FALLBACK, reasonCode, response);
    }
}
