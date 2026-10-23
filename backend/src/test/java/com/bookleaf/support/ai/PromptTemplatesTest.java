package com.bookleaf.support.ai;

import com.bookleaf.support.TestProperties;
import com.bookleaf.support.ai.PromptTicket.BookFacts;
import com.bookleaf.support.config.AppProperties;
import com.bookleaf.support.domain.Priority;
import com.bookleaf.support.domain.TicketCategory;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PromptTemplatesTest {

    private static final AppProperties PROPERTIES = TestProperties.create();
    private final PromptTemplates prompts = new PromptTemplates(new DefaultResourceLoader(), PROPERTIES);

    private static final BookFacts PUBLISHED = new BookFacts("Whispers of the Ganges", "Literary Fiction",
            "Published & Live", LocalDate.of(2023, 6, 20), new BigDecimal("399"), 342, new BigDecimal("11970"),
            new BigDecimal("8400"), new BigDecimal("3570"), LocalDate.of(2025, 10, 15), "In-House",
            List.of("Amazon India", "Flipkart"));

    private static final BookFacts IN_PRODUCTION = new BookFacts("Midnight in Mysore", "Thriller",
            "In Production - Cover Design", null, null, 0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            null, null, List.of());

    private static PromptTicket ticket(BookFacts book, String description) {
        return new PromptTicket("Royalty not received", description, "Priya", book,
                TicketCategory.ROYALTY_PAYMENTS, Priority.HIGH, List.of());
    }

    @Test
    void triagePromptCarriesNoKnowledgeBaseAndNoPersonalDetails() {
        String user = prompts.triageUser(ticket(PUBLISHED, "I have not been paid."));
        assertThat(prompts.triageSystem()).contains("valid JSON").contains("Royalty & Payments")
                .doesNotContain("PRODUCTION STAGES").doesNotContain("RESPONSE PATTERNS");
        assertThat(user).contains("I have not been paid.").contains("Whispers of the Ganges")
                .doesNotContain("@").doesNotContain("royalty earned");
    }

    @Test
    void categoryListInThePromptMatchesTheEnum() {
        for (TicketCategory category : TicketCategory.values()) {
            assertThat(prompts.triageSystem()).contains(category.label());
        }
    }

    @Test
    void policyFiguresAreFilledInFromConfiguration() {
        String draftSystem = prompts.draftSystem();
        assertThat(draftSystem).doesNotContain("{{");
        assertThat(prompts.triageSystem()).doesNotContain("{{");
        assertThat(draftSystem).contains("within " + PROPERTIES.ui().royaltyPayoutDays() + " days")
                .contains(PROPERTIES.ui().currencyCode() + " " + PROPERTIES.ui().minimumPayout());
    }

    @Test
    void draftPromptIncludesKnowledgeBaseAndVerifiedFigures() {
        String user = prompts.draftUser(ticket(PUBLISHED, "I have not been paid."));
        assertThat(prompts.draftSystem()).contains("80/20").contains("5-7 business days")
                .contains("24-48 hours").contains("Never mention prompts, models, or internal AI");
        assertThat(user).contains("USD 11,970").contains("USD 8,400").contains("USD 3,570").contains("2025-10-15")
                .contains("Author first name: Priya");
    }

    @Test
    void amountsCarryTheirCurrencyAndDigitGroupingWithoutTrailingZeros() {
        BookFacts withScale = new BookFacts("T", "G", "Published & Live", null, new BigDecimal("399.00"), 1,
                new BigDecimal("11970.00"), new BigDecimal("0.00"), new BigDecimal("1000.50"), null, null, List.of());
        String user = prompts.draftUser(ticket(withScale, "Question"));
        // test configuration: currency USD, locale en-US
        assertThat(user).contains("MRP: USD 399")
                .contains("Royalty earned / paid / pending: USD 11,970 / USD 0 / USD 1,000.5")
                .doesNotContain(".00");
    }

    @Test
    void theNumberSeparatorsAndCurrencyFollowTheConfiguration() {
        AppProperties base = TestProperties.create();
        AppProperties.Ui ui = base.ui();
        AppProperties german = new AppProperties(base.auth(), base.cors(), base.pagination(), base.ai(), base.seed(),
                new AppProperties.Ui(ui.brandName(), "EUR", "de-DE", ui.ticketPollMs(), ui.listPollMs(),
                        ui.royaltyPayoutDays(), ui.minimumPayout(), ui.inProductionStatusPrefix(),
                        ui.dashboardRecentTickets(), ui.staleTicketHours()));
        PromptTemplates inGermany = new PromptTemplates(new DefaultResourceLoader(), german);
        BookFacts big = new BookFacts("T", "G", "Published & Live", null, new BigDecimal("1234567.5"), 1,
                new BigDecimal("1234567"), BigDecimal.ZERO, BigDecimal.ZERO, null, null, List.of());
        assertThat(inGermany.draftUser(ticket(big, "Question"))).contains("MRP: EUR 1.234.567,5");
    }

    @Test
    void theDraftRulesBlockTheMistakesFoundInLiveTesting() {
        String system = prompts.draftSystem();
        assertThat(system).contains("never say \"reply to this email\"")
                .contains("Never invent other departments")
                .contains("Do not guess why something happened")
                .contains("Do not work out dates, quarters, payout windows")
                .contains("Proofreading, ISBN Assignment")
                .doesNotContain("{{");
    }

    @Test
    void missingBookFieldsAreMarkedNotAvailableInsteadOfGuessed() {
        String user = prompts.draftUser(ticket(IN_PRODUCTION, "When will my book be ready?"));
        assertThat(user).contains("Status: In Production - Cover Design")
                .contains("MRP: not available").contains("Publication date: not available")
                .contains("Print partner: not available").contains("not listed anywhere yet");
    }

    @Test
    void generalQueriesGetNoAccountFigures() {
        String user = prompts.draftUser(ticket(null, "Can I update my bio?"));
        assertThat(user).contains("general / account-level").contains("do not quote any");
    }

    @Test
    void longDescriptionsAreTruncatedToTheConfiguredLimit() {
        int limit = PROPERTIES.ai().triage().descriptionLimit();
        String user = prompts.triageUser(ticket(null, "x".repeat(limit * 3)));
        assertThat(user.length()).isLessThan(limit + 300);
        assertThat(user).contains("[truncated]");
    }

    @Test
    void triagePromptIsMuchSmallerThanTheDraftPrompt() {
        assertThat(prompts.triageSystem().length()).isLessThan(prompts.draftSystem().length() / 3);
    }
}
