package com.bookleaf.support.service;

import com.bookleaf.support.config.AppProperties;
import com.bookleaf.support.domain.MessageVisibility;
import com.bookleaf.support.domain.Priority;
import com.bookleaf.support.domain.Role;
import com.bookleaf.support.domain.Ticket;
import com.bookleaf.support.domain.TicketCategory;
import com.bookleaf.support.domain.TicketMessage;
import com.bookleaf.support.domain.TicketStatus;
import com.bookleaf.support.domain.TicketStatusRules;
import com.bookleaf.support.domain.UserAccount;
import com.bookleaf.support.dto.AdminTicketDtos.AiInfo;
import com.bookleaf.support.dto.AdminTicketDtos.AuthorInfo;
import com.bookleaf.support.dto.AdminTicketDtos.Detail;
import com.bookleaf.support.dto.AdminTicketDtos.Message;
import com.bookleaf.support.dto.AdminTicketDtos.NoteRequest;
import com.bookleaf.support.dto.AdminTicketDtos.PatchRequest;
import com.bookleaf.support.dto.AdminTicketDtos.QueueStats;
import com.bookleaf.support.dto.AdminTicketDtos.ReplyRequest;
import com.bookleaf.support.dto.AdminTicketDtos.Summary;
import com.bookleaf.support.dto.BookResponse;
import com.bookleaf.support.dto.PageResponse;
import com.bookleaf.support.repository.TicketMessageRepository;
import com.bookleaf.support.repository.TicketQueueQuery;
import com.bookleaf.support.repository.TicketRepository;
import com.bookleaf.support.repository.UserAccountRepository;
import com.bookleaf.support.web.error.ApiException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Admin operations on tickets. AuthFilter has already confirmed the caller is an admin. */
@Service
public class AdminTicketService {

    private final int maxPageSize;
    private final TicketRepository tickets;
    private final TicketMessageRepository messages;
    private final TicketQueueQuery queue;
    private final UserAccountRepository users;

    public AdminTicketService(TicketRepository tickets, TicketMessageRepository messages,
                              TicketQueueQuery queue, UserAccountRepository users, AppProperties properties) {
        this.maxPageSize = properties.pagination().maxSize();
        this.tickets = tickets;
        this.messages = messages;
        this.queue = queue;
        this.users = users;
    }

    /** Dates are calendar days in UTC; "to" includes the whole day. */
    @Transactional(readOnly = true)
    public PageResponse<Summary> list(TicketStatus status, TicketCategory category, Priority priority,
                                      LocalDate from, LocalDate to, int page, int size) {
        if (from != null && to != null && to.isBefore(from)) {
            throw ApiException.validation("to", "End date must not be before the start date.");
        }
        TicketQueueQuery.Filter filter = new TicketQueueQuery.Filter(
                status, category, priority,
                from == null ? null : from.atStartOfDay(ZoneOffset.UTC).toInstant(),
                to == null ? null : to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant());
        PageRequest pageRequest = PageRequest.of(
                Math.max(page, 0), Math.min(Math.max(size, 1), maxPageSize));
        Instant now = Instant.now();
        return PageResponse.of(queue.find(filter, pageRequest), ticket -> Summary.from(ticket, now));
    }

    /** Counts for the queue header: all tickets by status, and unresolved tickets by priority. */
    @Transactional(readOnly = true)
    public QueueStats stats() {
        Map<TicketStatus, Long> byStatus = new EnumMap<>(TicketStatus.class);
        Map<Priority, Long> byPriority = new EnumMap<>(Priority.class);
        for (TicketStatus status : TicketStatus.values()) byStatus.put(status, 0L);
        for (Priority priority : Priority.values()) byPriority.put(priority, 0L);

        List<TicketStatus> unresolved = Arrays.stream(TicketStatus.values())
                .filter(TicketStatusRules::isUnresolved)
                .toList();
        for (Object[] row : tickets.countByStatus()) {
            byStatus.put((TicketStatus) row[0], (Long) row[1]);
        }
        for (Object[] row : tickets.countByPriority(unresolved)) {
            byPriority.put((Priority) row[0], (Long) row[1]);
        }
        return new QueueStats(byStatus, byPriority, tickets.countUnassigned(unresolved));
    }

    @Transactional(readOnly = true)
    public Detail detail(UUID ticketId) {
        return toDetail(find(ticketId));
    }

    /** Updates only the fields that are present. AI suggestions are left untouched. */
    @Transactional
    public Detail update(UUID ticketId, PatchRequest request) {
        if (request.isEmpty()) {
            throw ApiException.validation("body", "Provide at least one of status, category, priority or assignedAdminId.");
        }
        Ticket ticket = find(ticketId);
        if (request.status() != null) {
            applyStatus(ticket, request.status());
        }
        if (request.category() != null) {
            ticket.setCategory(request.category());
            ticket.markTriageOverridden();
        }
        if (request.priority() != null) {
            ticket.setPriority(request.priority());
            ticket.markTriageOverridden();
        }
        if (request.assignedAdminId() != null) {
            UserAccount admin = users.findById(request.assignedAdminId())
                    .filter(user -> user.getRole() == Role.ADMIN)
                    .orElseThrow(() -> ApiException.validation("assignedAdminId", "Choose an existing admin."));
            ticket.setAssignedAdmin(admin);
        }
        return toDetail(ticket);
    }

    /** Internal notes are never shown to authors and do not change the ticket's updated time. */
    @Transactional
    public Detail addNote(UUID adminId, UUID ticketId, NoteRequest request) {
        Ticket ticket = find(ticketId);
        messages.save(newMessage(ticket, adminId, request.body(), MessageVisibility.INTERNAL));
        return toDetail(ticket);
    }

    /** Sends a public reply. The status only changes if the admin explicitly sends one. */
    @Transactional
    public Detail reply(UUID adminId, UUID ticketId, ReplyRequest request) {
        Ticket ticket = find(ticketId);
        if (request.status() != null) {
            applyStatus(ticket, request.status());
        }
        messages.save(newMessage(ticket, adminId, request.body(), MessageVisibility.PUBLIC));
        ticket.markReplied();
        return toDetail(ticket);
    }

    private Ticket find(UUID ticketId) {
        return tickets.findById(ticketId).orElseThrow(() ->
                ApiException.notFound("TICKET_NOT_FOUND", "Ticket not found."));
    }

    private TicketMessage newMessage(Ticket ticket, UUID adminId, String body, MessageVisibility visibility) {
        TicketMessage message = new TicketMessage();
        message.setTicket(ticket);
        message.setSender(users.getReferenceById(adminId));
        message.setBody(body.trim());
        message.setVisibility(visibility);
        return message;
    }

    private static void applyStatus(Ticket ticket, TicketStatus next) {
        TicketStatus current = ticket.getStatus();
        if (!TicketStatusRules.canTransition(current, next)) {
            throw ApiException.validation("status",
                    "A " + current + " ticket cannot be moved to " + next + ".");
        }
        ticket.setStatus(next);
        if (TicketStatusRules.isUnresolved(next)) {
            ticket.setResolvedAt(null);
        } else if (ticket.getResolvedAt() == null) {
            ticket.setResolvedAt(Instant.now());
        }
    }

    private Detail toDetail(Ticket ticket) {
        UserAccount author = ticket.getAuthor();
        return new Detail(
                Summary.from(ticket, Instant.now()),
                ticket.getDescription(),
                ticket.getResolvedAt(),
                ticket.getRepliedAt(),
                new AuthorInfo(author.getId(), author.getName(), author.getEmail(), author.getCity()),
                ticket.getBook() == null ? null : BookResponse.from(ticket.getBook()),
                new AiInfo(ticket.getAiOutcome(), ticket.getAiCategory(), ticket.getAiPriority(), ticket.getAiReason()),
                messages.findByTicket_IdOrderByCreatedAtAsc(ticket.getId()).stream().map(Message::from).toList());
    }
}
