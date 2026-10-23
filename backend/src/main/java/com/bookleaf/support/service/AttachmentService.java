package com.bookleaf.support.service;

import com.bookleaf.support.config.AppProperties;
import com.bookleaf.support.domain.Attachment;
import com.bookleaf.support.domain.Ticket;
import com.bookleaf.support.domain.TicketStatus;
import com.bookleaf.support.dto.AttachmentInfo;
import com.bookleaf.support.dto.ValidationLimits;
import com.bookleaf.support.repository.AttachmentRepository;
import com.bookleaf.support.repository.TicketRepository;
import com.bookleaf.support.repository.UserAccountRepository;
import com.bookleaf.support.web.error.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Stores and serves ticket attachments. Every check happens before a byte is saved. */
@Service
public class AttachmentService {

    /** Field name used in validation messages; matches the multipart part the client sends. */
    public static final String FIELD = "file";

    /** A file ready to be sent to the browser. */
    public record Download(String fileName, String contentType, byte[] content) {
    }

    private final AppProperties.Attachments limits;
    private final Set<String> allowedTypes;
    private final AttachmentRepository attachments;
    private final TicketRepository tickets;
    private final UserAccountRepository users;

    public AttachmentService(AppProperties properties, AttachmentRepository attachments,
                             TicketRepository tickets, UserAccountRepository users) {
        this.limits = properties.attachments();
        this.allowedTypes = Set.copyOf(limits.allowedTypes());
        this.attachments = attachments;
        this.tickets = tickets;
        this.users = users;
    }

    @Transactional(readOnly = true)
    public List<AttachmentInfo> list(UUID ticketId) {
        return attachments.findInfoByTicketId(ticketId);
    }

    /** An author adds a file to one of their own tickets; someone else's ticket looks like a missing one. */
    @Transactional
    public AttachmentInfo upload(UUID authorId, UUID ticketId, MultipartFile file) {
        Ticket ticket = tickets.findByIdAndAuthor_Id(ticketId, authorId).orElseThrow(() ->
                ApiException.notFound("TICKET_NOT_FOUND", "Ticket not found."));
        if (ticket.getStatus() == TicketStatus.CLOSED) {
            throw new ApiException(HttpStatus.CONFLICT, "TICKET_CLOSED",
                    "This ticket is closed. Please raise a new ticket if you need more help.");
        }
        if (file == null || file.isEmpty()) {
            throw ApiException.validation(FIELD, "Choose a file to attach.");
        }
        if (file.getSize() > limits.maxFileBytes()) {
            throw tooLarge();
        }
        if (attachments.countByTicket_Id(ticketId) >= limits.maxFilesPerTicket()) {
            throw new ApiException(HttpStatus.CONFLICT, "ATTACHMENT_LIMIT",
                    "A ticket can have at most " + limits.maxFilesPerTicket() + " attachments.");
        }

        byte[] content = read(file);
        String detected = FileTypes.detect(content).filter(allowedTypes::contains).orElseThrow(() ->
                new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_FILE_TYPE",
                        "This type of file is not allowed. Allowed: " + String.join(", ", limits.allowedTypes()) + "."));

        Attachment attachment = new Attachment();
        attachment.setTicket(ticket);
        attachment.setUploadedBy(users.getReferenceById(authorId));
        attachment.setFileName(cleanName(file.getOriginalFilename(), detected));
        attachment.setContentType(detected);
        attachment.setSizeBytes(content.length);
        attachment.setContent(content);
        Attachment saved = attachments.save(attachment);
        return new AttachmentInfo(saved.getId(), saved.getFileName(), saved.getContentType(), saved.getSizeBytes(),
                saved.getCreatedAt());
    }

    /** The author's own ticket only: another author's attachment is a 404, like their ticket. */
    @Transactional(readOnly = true)
    public Download downloadForAuthor(UUID authorId, UUID ticketId, UUID attachmentId) {
        tickets.findByIdAndAuthor_Id(ticketId, authorId).orElseThrow(() -> notFound());
        return download(ticketId, attachmentId);
    }

    @Transactional(readOnly = true)
    public Download downloadForAdmin(UUID ticketId, UUID attachmentId) {
        tickets.findById(ticketId).orElseThrow(() -> notFound());
        return download(ticketId, attachmentId);
    }

    private Download download(UUID ticketId, UUID attachmentId) {
        Attachment attachment = attachments.findByIdAndTicket_Id(attachmentId, ticketId).orElseThrow(() ->
                ApiException.notFound("ATTACHMENT_NOT_FOUND", "Attachment not found."));
        return new Download(attachment.getFileName(), attachment.getContentType(), attachment.getContent());
    }

    private static ApiException notFound() {
        return ApiException.notFound("TICKET_NOT_FOUND", "Ticket not found.");
    }

    private ApiException tooLarge() {
        return fileTooLarge(limits.maxFileBytes());
    }

    /** Also used by the exception handler when the container rejects an oversized upload before it reaches the service. */
    public static ApiException fileTooLarge(long maxBytes) {
        return new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE",
                "Files can be at most " + describeSize(maxBytes) + ".");
    }

    private static String describeSize(long bytes) {
        return bytes >= 1024 * 1024 && bytes % (1024 * 1024) == 0 ? bytes / (1024 * 1024) + " MB"
                : bytes >= 1024 ? Math.round(bytes / 1024.0) + " KB" : bytes + " bytes";
    }

    private static byte[] read(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException ex) {
            throw ApiException.validation(FIELD, "The file could not be read. Please try again.");
        }
    }

    /**
     * Keeps only the file's own name: no directories, no control characters, no characters that mean something
     * to a file system or a header. The extension is replaced by the one that matches the detected type.
     */
    static String cleanName(String original, String detectedType) {
        String name = original == null ? "" : original;
        name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        base = base.replaceAll("[^\\p{L}\\p{N} _-]", "_").replaceAll("_{2,}", "_").strip();
        String knownExtension = FileTypes.extensionFor(detectedType);
        String extension = knownExtension.isEmpty() ? "" : "." + knownExtension;
        int room = ValidationLimits.FILE_NAME_MAX - extension.length();
        if (base.length() > room) {
            base = base.substring(0, room).strip();
        }
        if (base.isEmpty() || base.chars().allMatch(c -> c == '_')) {
            base = "attachment";
        }
        return base + extension;
    }
}
