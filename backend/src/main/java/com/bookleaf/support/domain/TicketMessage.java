package com.bookleaf.support.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A reply or internal note on a ticket. Only PUBLIC messages are ever returned to
 * authors: the author queries filter on visibility, so INTERNAL notes cannot leak.
 */
@Entity
@Table(name = "ticket_messages", indexes = {
        @Index(name = "idx_ticket_messages_ticket_created", columnList = "ticket_id, createdAt")
})
public class TicketMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "ticket_id", nullable = false)
    private Ticket ticket;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "sender_id", nullable = false)
    private UserAccount sender;

    @Column(nullable = false, length = 10000)
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MessageVisibility visibility = MessageVisibility.PUBLIC;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public UUID getId() { return id; }

    public Ticket getTicket() { return ticket; }
    public void setTicket(Ticket ticket) { this.ticket = ticket; }

    public UserAccount getSender() { return sender; }
    public void setSender(UserAccount sender) { this.sender = sender; }

    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }

    public MessageVisibility getVisibility() { return visibility; }
    public void setVisibility(MessageVisibility visibility) { this.visibility = visibility; }

    public Instant getCreatedAt() { return createdAt; }
}
