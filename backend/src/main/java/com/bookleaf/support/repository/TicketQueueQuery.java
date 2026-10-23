package com.bookleaf.support.repository;

import com.bookleaf.support.domain.Book;
import com.bookleaf.support.domain.Priority;
import com.bookleaf.support.domain.Ticket;
import com.bookleaf.support.domain.TicketCategory;
import com.bookleaf.support.domain.TicketStatus;
import com.bookleaf.support.domain.UserAccount;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The admin ticket queue. Filters are optional and only the ones given are applied (built with
 * the Criteria API, so a missing filter never becomes a typed-null SQL parameter).
 *
 * Order: unresolved tickets (Open, In Progress) first; within each group by priority from
 * Critical to Low, then oldest first, so the most urgent and longest-waiting tickets lead.
 */
@Repository
public class TicketQueueQuery {

    private static final char LIKE_ESCAPE = '\\';

    /**
     * from is inclusive and toExclusive is exclusive. search matches the subject, the author's name or
     * email, or the book title (case-insensitive, as plain text). assignedAdminId and unassigned filter by
     * assignee. Every field is optional.
     */
    public record Filter(TicketStatus status, TicketCategory category, Priority priority,
                         Instant from, Instant toExclusive,
                         String search, UUID assignedAdminId, boolean unassigned, boolean awaitingReply) {
    }

    @PersistenceContext
    private EntityManager em;

    @SuppressWarnings("unchecked")
    public Page<Ticket> find(Filter filter, Pageable pageable) {
        CriteriaBuilder cb = em.getCriteriaBuilder();

        CriteriaQuery<Ticket> query = cb.createQuery(Ticket.class);
        Root<Ticket> ticket = query.from(Ticket.class);
        // fetching also gives us the join to filter on, so each association is joined once
        Join<Ticket, UserAccount> author = (Join<Ticket, UserAccount>) ticket.<Ticket, UserAccount>fetch("author", JoinType.LEFT);
        Join<Ticket, Book> book = (Join<Ticket, Book>) ticket.<Ticket, Book>fetch("book", JoinType.LEFT);
        ticket.fetch("assignedAdmin", JoinType.LEFT);
        query.select(ticket).where(predicates(cb, ticket, author, book, filter)).orderBy(ordering(cb, ticket));
        List<Ticket> rows = em.createQuery(query)
                .setFirstResult((int) pageable.getOffset())
                .setMaxResults(pageable.getPageSize())
                .getResultList();

        CriteriaQuery<Long> count = cb.createQuery(Long.class);
        Root<Ticket> countRoot = count.from(Ticket.class);
        Join<Ticket, UserAccount> countAuthor = countRoot.join("author", JoinType.LEFT);
        Join<Ticket, Book> countBook = countRoot.join("book", JoinType.LEFT);
        count.select(cb.count(countRoot)).where(predicates(cb, countRoot, countAuthor, countBook, filter));
        long total = em.createQuery(count).getSingleResult();

        return new PageImpl<>(rows, pageable, total);
    }

    private static Predicate[] predicates(CriteriaBuilder cb, Root<Ticket> ticket,
                                          Join<Ticket, UserAccount> author, Join<Ticket, Book> book, Filter filter) {
        List<Predicate> predicates = new ArrayList<>();
        if (filter.status() != null) {
            predicates.add(cb.equal(ticket.get("status"), filter.status()));
        }
        if (filter.category() != null) {
            predicates.add(cb.equal(ticket.get("category"), filter.category()));
        }
        if (filter.priority() != null) {
            predicates.add(cb.equal(ticket.get("priority"), filter.priority()));
        }
        if (filter.from() != null) {
            predicates.add(cb.greaterThanOrEqualTo(ticket.<Instant>get("createdAt"), filter.from()));
        }
        if (filter.toExclusive() != null) {
            predicates.add(cb.lessThan(ticket.<Instant>get("createdAt"), filter.toExclusive()));
        }
        if (filter.unassigned()) {
            predicates.add(cb.isNull(ticket.get("assignedAdmin")));
        } else if (filter.assignedAdminId() != null) {
            predicates.add(cb.equal(ticket.get("assignedAdmin").get("id"), filter.assignedAdminId()));
        }
        if (filter.awaitingReply()) {
            // unresolved, and no reply yet or the author wrote after the latest reply (same rule as Ticket.isAwaitingReply)
            predicates.add(ticket.get("status").in(TicketStatus.OPEN, TicketStatus.IN_PROGRESS));
            predicates.add(cb.or(
                    cb.isNull(ticket.get("repliedAt")),
                    cb.greaterThan(ticket.<Instant>get("lastAuthorMessageAt"), ticket.<Instant>get("repliedAt"))));
        }
        if (filter.search() != null && !filter.search().isBlank()) {
            String pattern = "%" + escapeLike(filter.search().strip().toLowerCase(Locale.ROOT)) + "%";
            predicates.add(cb.or(
                    cb.like(cb.lower(ticket.<String>get("subject")), pattern, LIKE_ESCAPE),
                    cb.like(cb.lower(author.<String>get("name")), pattern, LIKE_ESCAPE),
                    cb.like(cb.lower(author.<String>get("email")), pattern, LIKE_ESCAPE),
                    cb.like(cb.lower(book.<String>get("title")), pattern, LIKE_ESCAPE)));
        }
        return predicates.toArray(new Predicate[0]);
    }

    /** The search text is data, not a pattern: % and _ typed by the admin match themselves. */
    static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static List<Order> ordering(CriteriaBuilder cb, Root<Ticket> ticket) {
        var unresolvedFirst = cb.<Integer>selectCase()
                .when(ticket.get("status").in(TicketStatus.OPEN, TicketStatus.IN_PROGRESS), 0)
                .otherwise(1);
        var priorityRank = cb.<Integer>selectCase()
                .when(cb.equal(ticket.get("priority"), Priority.CRITICAL), 0)
                .when(cb.equal(ticket.get("priority"), Priority.HIGH), 1)
                .when(cb.equal(ticket.get("priority"), Priority.MEDIUM), 2)
                .otherwise(3);
        return List.of(
                cb.asc(unresolvedFirst),
                cb.asc(priorityRank),
                cb.asc(ticket.get("createdAt")));
    }
}
