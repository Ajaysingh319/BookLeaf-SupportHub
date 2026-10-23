package com.bookleaf.support.service;

import com.bookleaf.support.ai.TicketCreatedEvent;
import com.bookleaf.support.config.AppProperties;
import com.bookleaf.support.domain.Book;
import com.bookleaf.support.domain.MessageVisibility;
import com.bookleaf.support.domain.Ticket;
import com.bookleaf.support.dto.PageResponse;
import com.bookleaf.support.dto.TicketDtos.CreateTicketRequest;
import com.bookleaf.support.dto.TicketDtos.TicketDetail;
import com.bookleaf.support.dto.TicketDtos.TicketSummary;
import com.bookleaf.support.repository.BookRepository;
import com.bookleaf.support.repository.TicketMessageRepository;
import com.bookleaf.support.repository.TicketRepository;
import com.bookleaf.support.repository.UserAccountRepository;
import com.bookleaf.support.web.error.ApiException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class AuthorTicketService {

    private final int maxPageSize;
    private final TicketRepository tickets;
    private final TicketMessageRepository messages;
    private final BookRepository books;
    private final UserAccountRepository users;
    private final ApplicationEventPublisher events;

    public AuthorTicketService(TicketRepository tickets, TicketMessageRepository messages,
                               BookRepository books, UserAccountRepository users,
                               ApplicationEventPublisher events, AppProperties properties) {
        this.maxPageSize = properties.pagination().maxSize();
        this.tickets = tickets;
        this.messages = messages;
        this.books = books;
        this.users = users;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public PageResponse<TicketSummary> list(UUID authorId, int page, int size) {
        PageRequest pageRequest = PageRequest.of(
                Math.max(page, 0),
                Math.min(Math.max(size, 1), maxPageSize),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        return PageResponse.of(tickets.findByAuthor_Id(authorId, pageRequest), TicketSummary::from);
    }

    /**
     * The ticket is saved with safe defaults before any AI enrichment, so AI outages
     * can never block ticket creation.
     */
    @Transactional
    public TicketDetail create(UUID authorId, CreateTicketRequest request) {
        Book book = null;
        if (request.bookId() != null) {
            book = books.findByIdAndAuthor_Id(request.bookId(), authorId).orElseThrow(() ->
                    ApiException.validation("bookId", "Choose one of your own books, or General / Account Level."));
        }
        Ticket ticket = new Ticket();
        ticket.setAuthor(users.getReferenceById(authorId));
        ticket.setBook(book);
        ticket.setSubject(request.subject().trim());
        ticket.setDescription(request.description().trim());
        Ticket saved = tickets.save(ticket);
        // Handled only after this transaction commits, on a background thread.
        events.publishEvent(new TicketCreatedEvent(saved.getId()));
        return TicketDetail.from(saved, List.of());
    }

    /** A ticket that belongs to someone else looks exactly like a missing ticket (404). */
    @Transactional(readOnly = true)
    public TicketDetail get(UUID authorId, UUID ticketId) {
        Ticket ticket = tickets.findByIdAndAuthor_Id(ticketId, authorId).orElseThrow(() ->
                ApiException.notFound("TICKET_NOT_FOUND", "Ticket not found."));
        return TicketDetail.from(ticket,
                messages.findByTicket_IdAndVisibilityOrderByCreatedAtAsc(ticket.getId(), MessageVisibility.PUBLIC));
    }
}
