package com.bookleaf.support.repository;

import com.bookleaf.support.domain.Ticket;
import com.bookleaf.support.domain.TicketStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TicketRepository extends JpaRepository<Ticket, UUID> {

    Page<Ticket> findByAuthor_Id(UUID authorId, Pageable pageable);

    /** Rows of [status, count] for the admin summary. */
    @Query("select t.status, count(t) from Ticket t group by t.status")
    List<Object[]> countByStatus();

    /** Rows of [priority, count] among tickets in the given (unresolved) statuses. */
    @Query("select t.priority, count(t) from Ticket t where t.status in :statuses group by t.priority")
    List<Object[]> countByPriority(@Param("statuses") Collection<TicketStatus> statuses);

    @Query("select count(t) from Ticket t where t.status in :statuses and t.assignedAdmin is null")
    long countUnassigned(@Param("statuses") Collection<TicketStatus> statuses);

    /** Scoped by author so a ticket that belongs to someone else is simply "not found". */
    Optional<Ticket> findByIdAndAuthor_Id(UUID id, UUID authorId);
}
