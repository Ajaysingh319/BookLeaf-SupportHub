package com.bookleaf.support.repository;

import com.bookleaf.support.domain.Priority;
import com.bookleaf.support.domain.Ticket;
import com.bookleaf.support.domain.TicketCategory;
import com.bookleaf.support.domain.TicketStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
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

/**
 * The admin ticket queue. Filters are optional and only the ones given are applied (built with
 * the Criteria API, so a missing filter never becomes a typed-null SQL parameter).
 *
 * Order: unresolved tickets (Open, In Progress) first; within each group by priority from
 * Critical to Low, then oldest first, so the most urgent and longest-waiting tickets lead.
 */
@Repository
public class TicketQueueQuery {

    /** from is inclusive and toExclusive is exclusive; both optional. */
    public record Filter(TicketStatus status, TicketCategory category, Priority priority,
                         Instant from, Instant toExclusive) {
    }

    @PersistenceContext
    private EntityManager em;

    public Page<Ticket> find(Filter filter, Pageable pageable) {
        CriteriaBuilder cb = em.getCriteriaBuilder();

        CriteriaQuery<Ticket> query = cb.createQuery(Ticket.class);
        Root<Ticket> ticket = query.from(Ticket.class);
        ticket.fetch("author", JoinType.LEFT);
        ticket.fetch("book", JoinType.LEFT);
        ticket.fetch("assignedAdmin", JoinType.LEFT);
        query.select(ticket).where(predicates(cb, ticket, filter)).orderBy(ordering(cb, ticket));
        List<Ticket> rows = em.createQuery(query)
                .setFirstResult((int) pageable.getOffset())
                .setMaxResults(pageable.getPageSize())
                .getResultList();

        CriteriaQuery<Long> count = cb.createQuery(Long.class);
        Root<Ticket> countRoot = count.from(Ticket.class);
        count.select(cb.count(countRoot)).where(predicates(cb, countRoot, filter));
        long total = em.createQuery(count).getSingleResult();

        return new PageImpl<>(rows, pageable, total);
    }

    private static Predicate[] predicates(CriteriaBuilder cb, Root<Ticket> ticket, Filter filter) {
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
        return predicates.toArray(new Predicate[0]);
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
