package com.bookleaf.support.dto;

import com.bookleaf.support.domain.AiOutcome;
import com.bookleaf.support.domain.MessageVisibility;
import com.bookleaf.support.domain.Priority;
import com.bookleaf.support.domain.Role;
import com.bookleaf.support.domain.Ticket;
import com.bookleaf.support.domain.TicketCategory;
import com.bookleaf.support.domain.TicketMessage;
import com.bookleaf.support.domain.TicketStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Admin-facing ticket shapes. Unlike the author shapes, these include AI fields and internal notes. */
public final class AdminTicketDtos {

    private AdminTicketDtos() {
    }

    /** One row in the queue. ageHours helps spot old tickets; needsManualReview flags AI fallback. */
    public record Summary(
            UUID id,
            String subject,
            TicketStatus status,
            TicketCategory category,
            Priority priority,
            boolean needsManualReview,
            UUID authorId,
            String authorName,
            UUID bookId,
            String bookTitle,
            UUID assignedAdminId,
            String assignedAdminName,
            Instant createdAt,
            Instant updatedAt,
            long ageHours) {

        public static Summary from(Ticket ticket, Instant now) {
            return new Summary(
                    ticket.getId(),
                    ticket.getSubject(),
                    ticket.getStatus(),
                    ticket.getCategory(),
                    ticket.getPriority(),
                    ticket.getAiOutcome() == AiOutcome.FALLBACK,
                    ticket.getAuthor().getId(),
                    ticket.getAuthor().getName(),
                    ticket.getBook() == null ? null : ticket.getBook().getId(),
                    ticket.getBook() == null ? null : ticket.getBook().getTitle(),
                    ticket.getAssignedAdmin() == null ? null : ticket.getAssignedAdmin().getId(),
                    ticket.getAssignedAdmin() == null ? null : ticket.getAssignedAdmin().getName(),
                    ticket.getCreatedAt(),
                    ticket.getUpdatedAt(),
                    Duration.between(ticket.getCreatedAt(), now).toHours());
        }
    }

    public record AuthorInfo(UUID id, String name, String email, String city) {
    }

    public record AiInfo(
            AiOutcome outcome,
            TicketCategory suggestedCategory,
            Priority suggestedPriority,
            String reason) {
    }

    public record Message(
            UUID id,
            String body,
            String senderName,
            Role senderRole,
            MessageVisibility visibility,
            Instant createdAt) {

        public static Message from(TicketMessage message) {
            return new Message(
                    message.getId(),
                    message.getBody(),
                    message.getSender().getName(),
                    message.getSender().getRole(),
                    message.getVisibility(),
                    message.getCreatedAt());
        }
    }

    public record Detail(
            Summary summary,
            String description,
            Instant resolvedAt,
            Instant repliedAt,
            AuthorInfo author,
            BookResponse book,
            AiInfo ai,
            List<Message> messages) {
    }

    /**
     * Partial update: only the fields that are present are changed. Enum values that are not valid
     * are rejected with a 400 before this object is built.
     */
    public record PatchRequest(
            TicketStatus status,
            TicketCategory category,
            Priority priority,
            UUID assignedAdminId) {

        public boolean isEmpty() {
            return status == null && category == null && priority == null && assignedAdminId == null;
        }
    }

    public record NoteRequest(
            @NotBlank(message = "Note is required.")
            @Size(max = ValidationLimits.MESSAGE_MAX, message = "Note must be {max} characters or fewer.")
            String body) {
    }

    /** status is optional and explicit: a reply never changes the status unless the admin asks. */
    public record ReplyRequest(
            @NotBlank(message = "Reply is required.")
            @Size(max = ValidationLimits.MESSAGE_MAX, message = "Reply must be {max} characters or fewer.")
            String body,
            TicketStatus status) {
    }

    public record DraftResponse(String draft) {
    }

    /** Queue headline numbers (PRD P1: summary counts by status and priority). Every key is always present. */
    public record QueueStats(
            Map<TicketStatus, Long> byStatus,
            Map<Priority, Long> unresolvedByPriority,
            long unassignedUnresolved) {
    }
}
