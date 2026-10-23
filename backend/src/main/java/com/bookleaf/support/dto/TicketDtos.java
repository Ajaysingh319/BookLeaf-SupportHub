package com.bookleaf.support.dto;

import com.bookleaf.support.domain.Role;
import com.bookleaf.support.domain.Ticket;
import com.bookleaf.support.domain.TicketMessage;
import com.bookleaf.support.domain.TicketStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Author-facing ticket shapes. They carry no AI fields, assignee, or internal notes. */
public final class TicketDtos {

    private TicketDtos() {
    }

    public record CreateTicketRequest(
            /* Null means "General / Account Level". */
            UUID bookId,

            @NotBlank(message = "Subject is required.")
            @Size(min = ValidationLimits.SUBJECT_MIN, max = ValidationLimits.SUBJECT_MAX,
                    message = "Subject must be between {min} and {max} characters.")
            String subject,

            @NotBlank(message = "Description is required.")
            @Size(min = ValidationLimits.DESCRIPTION_MIN, max = ValidationLimits.DESCRIPTION_MAX,
                    message = "Description must be between {min} and {max} characters.")
            String description) {
    }

    /** A follow-up message from the author on one of their own tickets. */
    public record AuthorReplyRequest(
            @NotBlank(message = "Message is required.")
            @Size(max = ValidationLimits.MESSAGE_MAX, message = "Message must be {max} characters or fewer.")
            String body) {
    }

    public record TicketSummary(
            UUID id,
            String subject,
            TicketStatus status,
            UUID bookId,
            String bookTitle,
            Instant createdAt,
            Instant updatedAt) {

        public static TicketSummary from(Ticket ticket) {
            return new TicketSummary(
                    ticket.getId(),
                    ticket.getSubject(),
                    ticket.getStatus(),
                    ticket.getBook() == null ? null : ticket.getBook().getId(),
                    ticket.getBook() == null ? null : ticket.getBook().getTitle(),
                    ticket.getCreatedAt(),
                    ticket.getUpdatedAt());
        }
    }

    public record MessageResponse(UUID id, String body, String senderName, Role senderRole, Instant createdAt) {

        public static MessageResponse from(TicketMessage message) {
            return new MessageResponse(
                    message.getId(),
                    message.getBody(),
                    message.getSender().getName(),
                    message.getSender().getRole(),
                    message.getCreatedAt());
        }
    }

    public record TicketDetail(
            UUID id,
            String subject,
            String description,
            TicketStatus status,
            UUID bookId,
            String bookTitle,
            Instant createdAt,
            Instant updatedAt,
            List<MessageResponse> messages,
            List<AttachmentInfo> attachments) {

        public static TicketDetail from(Ticket ticket, List<TicketMessage> publicMessages,
                                        List<AttachmentInfo> attachments) {
            return new TicketDetail(
                    ticket.getId(),
                    ticket.getSubject(),
                    ticket.getDescription(),
                    ticket.getStatus(),
                    ticket.getBook() == null ? null : ticket.getBook().getId(),
                    ticket.getBook() == null ? null : ticket.getBook().getTitle(),
                    ticket.getCreatedAt(),
                    ticket.getUpdatedAt(),
                    publicMessages.stream().map(MessageResponse::from).toList(),
                    attachments);
        }
    }
}
