package com.bookleaf.support.ai;

import java.util.UUID;

/** Published inside the ticket-creation transaction; handled only after that transaction commits. */
public record TicketCreatedEvent(UUID ticketId) {
}
