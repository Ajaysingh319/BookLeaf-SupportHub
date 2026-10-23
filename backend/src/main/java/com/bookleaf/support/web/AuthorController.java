package com.bookleaf.support.web;

import com.bookleaf.support.dto.AttachmentInfo;
import com.bookleaf.support.dto.BookResponse;
import com.bookleaf.support.dto.PageResponse;
import com.bookleaf.support.dto.TicketDtos.AuthorReplyRequest;
import com.bookleaf.support.dto.TicketDtos.CreateTicketRequest;
import com.bookleaf.support.dto.TicketDtos.TicketDetail;
import com.bookleaf.support.dto.TicketDtos.TicketSummary;
import com.bookleaf.support.security.AuthUser;
import com.bookleaf.support.service.AttachmentService;
import com.bookleaf.support.service.AuthorBookService;
import com.bookleaf.support.service.AuthorTicketService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Author endpoints. AuthFilter has already confirmed the caller is an author. */
@RestController
@RequestMapping("/api/v1/author")
public class AuthorController {

    private final AuthorBookService bookService;
    private final AuthorTicketService ticketService;
    private final AttachmentService attachmentService;

    public AuthorController(AuthorBookService bookService, AuthorTicketService ticketService,
                            AttachmentService attachmentService) {
        this.bookService = bookService;
        this.ticketService = ticketService;
        this.attachmentService = attachmentService;
    }

    @GetMapping("/books")
    public List<BookResponse> books(@RequestAttribute(AuthUser.REQUEST_ATTRIBUTE) AuthUser user) {
        return bookService.listForAuthor(user.id());
    }

    @GetMapping("/tickets")
    public PageResponse<TicketSummary> tickets(
            @RequestAttribute(AuthUser.REQUEST_ATTRIBUTE) AuthUser user,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "${app.pagination.default-size}") int size) {
        return ticketService.list(user.id(), page, size);
    }

    @PostMapping("/tickets")
    @ResponseStatus(HttpStatus.CREATED)
    public TicketDetail createTicket(
            @RequestAttribute(AuthUser.REQUEST_ATTRIBUTE) AuthUser user,
            @Valid @RequestBody CreateTicketRequest request) {
        return ticketService.create(user.id(), request);
    }

    /** A follow-up message on one of the author's own tickets (reopens a resolved ticket). */
    @PostMapping("/tickets/{ticketId}/replies")
    @ResponseStatus(HttpStatus.CREATED)
    public TicketDetail reply(
            @RequestAttribute(AuthUser.REQUEST_ATTRIBUTE) AuthUser user,
            @PathVariable UUID ticketId,
            @Valid @RequestBody AuthorReplyRequest request) {
        return ticketService.reply(user.id(), ticketId, request);
    }

    /** One file per request, sent as the multipart part "file". */
    @PostMapping(value = "/tickets/{ticketId}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public AttachmentInfo upload(
            @RequestAttribute(AuthUser.REQUEST_ATTRIBUTE) AuthUser user,
            @PathVariable UUID ticketId,
            @RequestPart("file") MultipartFile file) {
        return attachmentService.upload(user.id(), ticketId, file);
    }

    @GetMapping("/tickets/{ticketId}/attachments/{attachmentId}")
    public ResponseEntity<byte[]> downloadAttachment(
            @RequestAttribute(AuthUser.REQUEST_ATTRIBUTE) AuthUser user,
            @PathVariable UUID ticketId,
            @PathVariable UUID attachmentId) {
        return AttachmentResponses.download(attachmentService.downloadForAuthor(user.id(), ticketId, attachmentId));
    }

    @GetMapping("/tickets/{ticketId}")
    public TicketDetail ticket(
            @RequestAttribute(AuthUser.REQUEST_ATTRIBUTE) AuthUser user,
            @PathVariable UUID ticketId) {
        return ticketService.get(user.id(), ticketId);
    }
}
