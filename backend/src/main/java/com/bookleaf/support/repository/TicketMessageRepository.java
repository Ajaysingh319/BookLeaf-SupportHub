package com.bookleaf.support.repository;

import com.bookleaf.support.domain.MessageVisibility;
import com.bookleaf.support.domain.TicketMessage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TicketMessageRepository extends JpaRepository<TicketMessage, UUID> {

    /** Author view: PUBLIC only. Internal notes can never come back through this query. */
    List<TicketMessage> findByTicket_IdAndVisibilityOrderByCreatedAtAsc(UUID ticketId, MessageVisibility visibility);

    /** Admin view: public replies and internal notes together. */
    List<TicketMessage> findByTicket_IdOrderByCreatedAtAsc(UUID ticketId);

    /** The newest public replies (page size = how many), used to give the AI draft some conversation context. */
    List<TicketMessage> findByTicket_IdAndVisibilityOrderByCreatedAtDesc(UUID ticketId, MessageVisibility visibility,
                                                                       Pageable limit);
}
