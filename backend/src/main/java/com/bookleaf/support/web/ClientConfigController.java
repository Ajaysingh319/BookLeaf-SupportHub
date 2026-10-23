package com.bookleaf.support.web;

import com.bookleaf.support.config.AppProperties;
import com.bookleaf.support.domain.Priority;
import com.bookleaf.support.domain.TicketCategory;
import com.bookleaf.support.domain.TicketStatus;
import com.bookleaf.support.domain.TicketStatusRules;
import com.bookleaf.support.dto.ValidationLimits;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;

/**
 * GET /api/v1/config: the settings the browser client needs at startup. They come from server
 * configuration and from the same enums and rules the API enforces, so the client holds no copies
 * of limits, intervals, policy figures, labels or allowed status changes.
 * Public on purpose (the login page needs it) and contains no secrets.
 */
@RestController
@RequestMapping("/api/v1/config")
public class ClientConfigController {

    public record Validation(int subjectMin, int subjectMax, int descriptionMin, int descriptionMax, int messageMax) {
    }

    /** What the attach-a-file control offers; the server enforces the same limits. */
    public record Attachments(int maxFileBytes, int maxFilesPerTicket, List<String> allowedTypes) {
    }

    public record Policy(int royaltyPayoutDays, int minimumPayout) {
    }

    public record Option(String value, String label) {
    }

    /** allowedNext lists the statuses a ticket in this status may be moved to (TDD section 8). */
    public record StatusOption(String value, String label, List<String> allowedNext) {
    }

    public record Options(List<StatusOption> statuses, List<Option> categories, List<Option> priorities) {
    }

    public record ClientConfig(
            String brandName,
            String currencyCode,
            String locale,
            int ticketPollMs,
            int listPollMs,
            int defaultPageSize,
            String inProductionStatusPrefix,
            int dashboardRecentTickets,
            int staleTicketHours,
            Policy policy,
            Validation validation,
            Attachments attachments,
            Options options) {
    }

    private final ClientConfig config;

    public ClientConfigController(AppProperties properties) {
        AppProperties.Ui ui = properties.ui();
        this.config = new ClientConfig(
                ui.brandName(),
                ui.currencyCode(),
                ui.locale(),
                ui.ticketPollMs(),
                ui.listPollMs(),
                properties.pagination().defaultSize(),
                ui.inProductionStatusPrefix(),
                ui.dashboardRecentTickets(),
                ui.staleTicketHours(),
                new Policy(ui.royaltyPayoutDays(), ui.minimumPayout()),
                new Validation(ValidationLimits.SUBJECT_MIN, ValidationLimits.SUBJECT_MAX,
                        ValidationLimits.DESCRIPTION_MIN, ValidationLimits.DESCRIPTION_MAX,
                        ValidationLimits.MESSAGE_MAX),
                new Attachments(properties.attachments().maxFileBytes(), properties.attachments().maxFilesPerTicket(),
                        properties.attachments().allowedTypes()),
                options());
    }

    private static Options options() {
        List<StatusOption> statuses = Arrays.stream(TicketStatus.values())
                .map(status -> new StatusOption(status.name(), status.label(),
                        Arrays.stream(TicketStatus.values())
                                .filter(next -> next != status && TicketStatusRules.canTransition(status, next))
                                .map(TicketStatus::name)
                                .toList()))
                .toList();
        List<Option> categories = Arrays.stream(TicketCategory.values())
                .map(category -> new Option(category.name(), category.label()))
                .toList();
        List<Option> priorities = Arrays.stream(Priority.values())
                .map(priority -> new Option(priority.name(), priority.label()))
                .toList();
        return new Options(statuses, categories, priorities);
    }

    @GetMapping
    public ClientConfig config() {
        return config;
    }
}
