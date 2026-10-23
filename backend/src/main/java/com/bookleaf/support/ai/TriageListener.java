package com.bookleaf.support.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Runs triage after the ticket is safely committed, on a background thread, so the author's
 * "submit" request returns immediately and AI problems can never roll back or delay a ticket.
 */
@Component
public class TriageListener {

    private static final Logger log = LoggerFactory.getLogger(TriageListener.class);

    private final AiTriageService triage;

    public TriageListener(AiTriageService triage) {
        this.triage = triage;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTicketCreated(TicketCreatedEvent event) {
        try {
            triage.triage(event.ticketId());
        } catch (RuntimeException ex) {
            log.error("Triage failed unexpectedly for ticket {}", event.ticketId(), ex);
        }
    }
}
