package com.bookleaf.support.ai;

import com.bookleaf.support.ai.PromptTicket.BookFacts;
import com.bookleaf.support.config.AppProperties;
import com.bookleaf.support.domain.AiOutcome;
import com.bookleaf.support.domain.MessageVisibility;
import com.bookleaf.support.domain.Ticket;
import com.bookleaf.support.domain.TicketMessage;
import com.bookleaf.support.repository.TicketMessageRepository;
import com.bookleaf.support.repository.TicketRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Database access for the AI features, kept separate so the network call to the model happens
 * outside any database transaction: load, release the connection, call the model, then save.
 */
@Service
public class AiTicketContext {

    private final TicketRepository tickets;
    private final TicketMessageRepository messages;
    private final int recentReplies;

    public AiTicketContext(TicketRepository tickets, TicketMessageRepository messages, AppProperties properties) {
        this.tickets = tickets;
        this.messages = messages;
        this.recentReplies = properties.ai().draft().recentReplies();
    }

    @Transactional(readOnly = true)
    public Optional<PromptTicket> load(UUID ticketId) {
        return tickets.findById(ticketId).map(ticket -> {
            List<String> replies = new ArrayList<>(
                    messages.findByTicket_IdAndVisibilityOrderByCreatedAtDesc(
                                    ticketId, MessageVisibility.PUBLIC, PageRequest.of(0, recentReplies))
                            .stream().map(TicketMessage::getBody).toList());
            Collections.reverse(replies);
            return new PromptTicket(
                    ticket.getSubject(),
                    ticket.getDescription(),
                    firstName(ticket.getAuthor().getName()),
                    ticket.getBook() == null ? null : BookFacts.from(ticket.getBook()),
                    ticket.getCategory(),
                    ticket.getPriority(),
                    replies);
        });
    }

    /**
     * Stores the triage outcome. With a successful AI answer the suggestion is saved separately
     * and also becomes the starting value of the final fields; on fallback only the final
     * fields change and the AI fields stay empty. Skipped if triage already ran.
     */
    @Transactional
    public void applyTriage(UUID ticketId, TriageResult result, boolean fromAi) {
        Ticket ticket = tickets.findById(ticketId).orElse(null);
        if (ticket == null || ticket.getAiOutcome() != AiOutcome.PENDING) {
            return;
        }
        ticket.setCategory(result.category());
        ticket.setPriority(result.priority());
        ticket.setAiReason(result.reason());
        if (fromAi) {
            ticket.setAiCategory(result.category());
            ticket.setAiPriority(result.priority());
            ticket.setAiOutcome(AiOutcome.SUCCESS);
        } else {
            ticket.setAiOutcome(AiOutcome.FALLBACK);
        }
    }

    private static String firstName(String fullName) {
        if (fullName == null || fullName.isBlank()) {
            return "there";
        }
        return fullName.strip().split("\\s+")[0];
    }
}
