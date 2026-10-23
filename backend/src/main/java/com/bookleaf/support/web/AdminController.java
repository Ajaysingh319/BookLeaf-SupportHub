package com.bookleaf.support.web;

import com.bookleaf.support.ai.AiDraftService;
import com.bookleaf.support.domain.Priority;
import com.bookleaf.support.domain.TicketCategory;
import com.bookleaf.support.domain.TicketStatus;
import com.bookleaf.support.dto.AdminTicketDtos.Detail;
import com.bookleaf.support.dto.AdminTicketDtos.DraftResponse;
import com.bookleaf.support.dto.AdminTicketDtos.NoteRequest;
import com.bookleaf.support.dto.AdminTicketDtos.PatchRequest;
import com.bookleaf.support.dto.AdminTicketDtos.QueueStats;
import com.bookleaf.support.dto.AdminTicketDtos.ReplyRequest;
import com.bookleaf.support.dto.AdminTicketDtos.Summary;
import com.bookleaf.support.dto.PageResponse;
import com.bookleaf.support.security.AuthUser;
import com.bookleaf.support.service.AdminTicketService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

/** Admin endpoints (TDD section 6). AuthFilter has already confirmed the caller is an admin. */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    private final AdminTicketService ticketService;
    private final AiDraftService draftService;

    public AdminController(AdminTicketService ticketService, AiDraftService draftService) {
        this.ticketService = ticketService;
        this.draftService = draftService;
    }

    @GetMapping("/tickets")
    public PageResponse<Summary> tickets(
            @RequestParam(required = false) TicketStatus status,
            @RequestParam(required = false) TicketCategory category,
            @RequestParam(required = false) Priority priority,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "${app.pagination.default-size}") int size) {
        return ticketService.list(status, category, priority, from, to, page, size);
    }

    @GetMapping("/tickets/summary")
    public QueueStats summary() {
        return ticketService.stats();
    }

    @GetMapping("/tickets/{ticketId}")
    public Detail ticket(@PathVariable UUID ticketId) {
        return ticketService.detail(ticketId);
    }

    @PatchMapping("/tickets/{ticketId}")
    public Detail update(@PathVariable UUID ticketId, @RequestBody PatchRequest request) {
        return ticketService.update(ticketId, request);
    }

    @PostMapping("/tickets/{ticketId}/notes")
    @ResponseStatus(HttpStatus.CREATED)
    public Detail addNote(
            @RequestAttribute(AuthUser.REQUEST_ATTRIBUTE) AuthUser admin,
            @PathVariable UUID ticketId,
            @Valid @RequestBody NoteRequest request) {
        return ticketService.addNote(admin.id(), ticketId, request);
    }

    /** Returns a suggested reply for the admin to edit. Nothing is saved or sent. */
    @PostMapping("/tickets/{ticketId}/ai-draft")
    public DraftResponse draft(@PathVariable UUID ticketId) {
        return new DraftResponse(draftService.generate(ticketId));
    }

    @PostMapping("/tickets/{ticketId}/replies")
    @ResponseStatus(HttpStatus.CREATED)
    public Detail reply(
            @RequestAttribute(AuthUser.REQUEST_ATTRIBUTE) AuthUser admin,
            @PathVariable UUID ticketId,
            @Valid @RequestBody ReplyRequest request) {
        return ticketService.reply(admin.id(), ticketId, request);
    }
}
