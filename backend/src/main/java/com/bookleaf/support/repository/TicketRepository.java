package com.bookleaf.support.repository;

import com.bookleaf.support.domain.Ticket;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface TicketRepository extends JpaRepository<Ticket, UUID> {

    Page<Ticket> findByAuthor_Id(UUID authorId, Pageable pageable);

    /** Scoped by author so a ticket that belongs to someone else is simply "not found". */
    Optional<Ticket> findByIdAndAuthor_Id(UUID id, UUID authorId);
}
