package com.bookleaf.support.ai;

import com.bookleaf.support.ai.PromptTicket.BookFacts;
import com.bookleaf.support.config.AppProperties;
import com.bookleaf.support.domain.TicketCategory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.Locale;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Builds the prompts for the two AI tasks. They are kept separate on purpose (TDD 7.2): triage is a
 * tiny JSON-only call with no knowledge base; drafting carries the knowledge base but is only called
 * when an admin asks for it. The prompt wording and the knowledge base live in resource files
 * (see app.ai.prompts-location); policy figures in them are filled in from configuration.
 */
@Component
public class PromptTemplates {

    private static final String TRIAGE_FILE = "triage-system.txt";
    private static final String DRAFT_FILE = "draft-instruction.txt";
    private static final String KNOWLEDGE_BASE_FILE = "knowledge-base.txt";
    private static final String PATTERNS_FILE = "response-patterns.txt";

    private final AppProperties.Triage triage;
    private final AppProperties.Draft draft;
    private final Locale locale;
    private final String currencyCode;
    private final String triageSystem;
    private final String draftSystem;

    public PromptTemplates(ResourceLoader resourceLoader, AppProperties properties) {
        this.triage = properties.ai().triage();
        this.draft = properties.ai().draft();
        this.locale = Locale.forLanguageTag(properties.ui().locale());
        this.currencyCode = properties.ui().currencyCode();
        String location = properties.ai().promptsLocation();
        AppProperties.Ui ui = properties.ui();

        this.triageSystem = fill(read(resourceLoader, location + TRIAGE_FILE), ui);
        String knowledgeBase = fill(read(resourceLoader, location + KNOWLEDGE_BASE_FILE), ui);
        String patterns = fill(read(resourceLoader, location + PATTERNS_FILE), ui);
        this.draftSystem = read(resourceLoader, location + DRAFT_FILE)
                + "\nKNOWLEDGE BASE:\n" + knowledgeBase
                + "\nRESPONSE PATTERNS:\n" + patterns;
    }

    /** The category list is taken from the enum, so the prompt can never drift from the code. */
    private static String fill(String text, AppProperties.Ui ui) {
        String categories = Arrays.stream(TicketCategory.values())
                .map(TicketCategory::label)
                .collect(Collectors.joining("; "));
        return text
                .replace("{{categories}}", categories)
                .replace("{{royaltyPayoutDays}}", String.valueOf(ui.royaltyPayoutDays()))
                .replace("{{minimumPayout}}", String.valueOf(ui.minimumPayout()))
                .replace("{{currencyCode}}", ui.currencyCode());
    }

    private static String read(ResourceLoader resourceLoader, String location) {
        Resource resource = resourceLoader.getResource(location);
        try {
            return new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (IOException ex) {
            throw new UncheckedIOException("Cannot read prompt file " + location, ex);
        }
    }

    public String triageSystem() {
        return triageSystem;
    }

    public String draftSystem() {
        return draftSystem;
    }

    public String triageUser(PromptTicket ticket) {
        StringBuilder text = new StringBuilder();
        text.append("Ticket subject: ").append(ticket.subject()).append('\n');
        text.append("Ticket description: ").append(limit(ticket.description(), triage.descriptionLimit())).append('\n');
        if (ticket.book() == null) {
            text.append("Book: none (general / account-level query)\n");
        } else {
            BookFacts book = ticket.book();
            text.append("Book: ").append(book.title()).append(" | production/publishing status: ")
                    .append(book.status()).append('\n');
        }
        return text.toString();
    }

    public String draftUser(PromptTicket ticket) {
        StringBuilder text = new StringBuilder();
        text.append("Author first name: ").append(ticket.authorFirstName()).append('\n');
        text.append("Ticket category: ").append(ticket.category().label())
                .append(" | priority: ").append(ticket.priority().label()).append('\n');
        text.append("Ticket subject: ").append(ticket.subject()).append('\n');
        text.append("Ticket description: ").append(limit(ticket.description(), draft.descriptionLimit())).append('\n');
        text.append('\n');
        text.append(bookSection(ticket.book()));
        List<String> replies = ticket.recentPublicReplies();
        if (replies != null && !replies.isEmpty()) {
            text.append("\nEarlier replies already sent to the author (oldest first, trimmed):\n");
            for (String reply : replies) {
                text.append("- ").append(limit(reply, draft.replyLimit())).append('\n');
            }
        }
        text.append("\nWrite the reply the support representative will review and send.");
        return text.toString();
    }

    private String bookSection(BookFacts book) {
        if (book == null) {
            return "Book record: none. This is a general / account-level query, so no account figures were provided; "
                    + "do not quote any.\n";
        }
        String platforms = book.availableOn().isEmpty()
                ? "not listed anywhere yet"
                : String.join(", ", book.availableOn());
        return "Book record (verified system data; use only these figures):\n"
                + "- Title: " + book.title() + '\n'
                + "- Genre: " + book.genre() + '\n'
                + "- Status: " + book.status() + '\n'
                + "- Publication date: " + valueOrNa(book.publicationDate()) + '\n'
                + "- MRP: " + money(book.mrp()) + '\n'
                + "- Copies sold: " + valueOrNa(book.copiesSold()) + '\n'
                + "- Royalty earned / paid / pending: " + money(book.royaltyEarned()) + " / "
                + money(book.royaltyPaid()) + " / " + money(book.royaltyPending()) + '\n'
                + "- Last royalty payout date: " + valueOrNa(book.lastRoyaltyPayoutDate()) + '\n'
                + "- Print partner: " + valueOrNa(book.printPartner()) + '\n'
                + "- Listed on: " + platforms + '\n';
    }

    /**
     * An amount with its currency code and the configured locale's digit grouping (INR 11,970), no trailing
     * zeros from the database scale, so the model quotes figures the same way every time. Null means not available.
     */
    private String money(BigDecimal amount) {
        if (amount == null) {
            return "not available";
        }
        NumberFormat format = NumberFormat.getNumberInstance(locale);
        format.setMinimumFractionDigits(0);
        format.setMaximumFractionDigits(2);
        return currencyCode + " " + format.format(amount);
    }

    private static String valueOrNa(Object value) {
        return value == null ? "not available" : value.toString();
    }

    static String limit(String text, int max) {
        if (text == null) {
            return "";
        }
        String trimmed = text.strip();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max) + " [truncated]";
    }
}
