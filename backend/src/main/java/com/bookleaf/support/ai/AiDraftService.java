package com.bookleaf.support.ai;

import com.bookleaf.support.config.AppProperties;
import com.bookleaf.support.domain.AiEvent;
import com.bookleaf.support.web.error.ApiException;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Drafts a reply for an admin to review and edit. It runs only when the admin asks for it, and
 * the result is returned to the admin, never saved or sent. If the AI fails the caller gets a
 * 503 and the admin simply writes the reply by hand.
 */
@Service
public class AiDraftService {

    private final AiTicketContext context;
    private final LlmClient llm;
    private final PromptTemplates prompts;
    private final AiEventRecorder events;
    private final AppProperties.Draft settings;

    public AiDraftService(AiTicketContext context, LlmClient llm, PromptTemplates prompts,
                          AiEventRecorder events, AppProperties properties) {
        this.context = context;
        this.llm = llm;
        this.prompts = prompts;
        this.events = events;
        this.settings = properties.ai().draft();
    }

    public String generate(UUID ticketId) {
        PromptTicket ticket = context.load(ticketId).orElseThrow(() ->
                ApiException.notFound("TICKET_NOT_FOUND", "Ticket not found."));
        try {
            LlmClient.LlmResult response = llm.complete(new LlmClient.LlmRequest(
                    prompts.draftSystem(), prompts.draftUser(ticket),
                    settings.temperature(), settings.maxTokens(), false));
            String draft = response.content() == null ? "" : response.content().strip();
            if (draft.isEmpty() || draft.length() > settings.maxLength()) {
                events.record(ticketId, AiEvent.Task.DRAFT, AiEvent.Outcome.FAILED, "invalid_output", response);
                throw new AiUnavailableException("invalid_output");
            }
            events.record(ticketId, AiEvent.Task.DRAFT, AiEvent.Outcome.SUCCESS, null, response);
            return draft;
        } catch (AiUnavailableException ex) {
            if (!"invalid_output".equals(ex.reasonCode())) {
                events.record(ticketId, AiEvent.Task.DRAFT, AiEvent.Outcome.FAILED, ex.reasonCode(), null);
            }
            throw ex;
        }
    }
}
