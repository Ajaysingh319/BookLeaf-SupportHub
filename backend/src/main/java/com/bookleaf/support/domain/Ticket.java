package com.bookleaf.support.domain;

import com.bookleaf.support.dto.ValidationLimits;
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
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A support ticket. category and priority are the final values (admin-editable);
 * aiCategory and aiPriority keep what the model suggested, separately.
 */
@Entity
@Table(name = "tickets", indexes = {
        @Index(name = "idx_tickets_status_priority_created", columnList = "status, priority, createdAt"),
        @Index(name = "idx_tickets_author_created", columnList = "author_id, createdAt")
})
public class Ticket {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "author_id", nullable = false)
    private UserAccount author;

    /** Null means "General / Account Level". */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "book_id")
    private Book book;

    @Column(nullable = false, length = ValidationLimits.SUBJECT_MAX)
    private String subject;

    @Column(nullable = false, length = ValidationLimits.DESCRIPTION_MAX)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TicketStatus status = TicketStatus.OPEN;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TicketCategory category = TicketCategory.GENERAL_INQUIRY;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Priority priority = Priority.MEDIUM;

    @Enumerated(EnumType.STRING)
    private TicketCategory aiCategory;

    @Enumerated(EnumType.STRING)
    private Priority aiPriority;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AiOutcome aiOutcome = AiOutcome.PENDING;

    private String aiReason;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_admin_id")
    private UserAccount assignedAdmin;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    private Instant resolvedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    /** Marks the ticket as changed now, so a public reply is visible to the author's next refresh. */
    public void touch() {
        updatedAt = Instant.now();
    }

    public UUID getId() { return id; }

    public UserAccount getAuthor() { return author; }
    public void setAuthor(UserAccount author) { this.author = author; }

    public Book getBook() { return book; }
    public void setBook(Book book) { this.book = book; }

    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public TicketStatus getStatus() { return status; }
    public void setStatus(TicketStatus status) { this.status = status; }

    public TicketCategory getCategory() { return category; }
    public void setCategory(TicketCategory category) { this.category = category; }

    public Priority getPriority() { return priority; }
    public void setPriority(Priority priority) { this.priority = priority; }

    public TicketCategory getAiCategory() { return aiCategory; }
    public void setAiCategory(TicketCategory aiCategory) { this.aiCategory = aiCategory; }

    public Priority getAiPriority() { return aiPriority; }
    public void setAiPriority(Priority aiPriority) { this.aiPriority = aiPriority; }

    public AiOutcome getAiOutcome() { return aiOutcome; }
    public void setAiOutcome(AiOutcome aiOutcome) { this.aiOutcome = aiOutcome; }

    public String getAiReason() { return aiReason; }
    public void setAiReason(String aiReason) { this.aiReason = aiReason; }

    public UserAccount getAssignedAdmin() { return assignedAdmin; }
    public void setAssignedAdmin(UserAccount assignedAdmin) { this.assignedAdmin = assignedAdmin; }

    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public Instant getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(Instant resolvedAt) { this.resolvedAt = resolvedAt; }
}
