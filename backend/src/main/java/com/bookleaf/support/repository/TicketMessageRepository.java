package com.bookleaf.support.repository;

import com.bookleaf.support.domain.MessageVisibility;
import com.bookleaf.support.domain.TicketMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TicketMessageRepository extends JpaRepository<TicketMessage, UUID> {

    List<TicketMessage> findByTicket_IdAndVisibilityOrderByCreatedAtAsc(UUID ticketId, MessageVisibility visibility);
}
