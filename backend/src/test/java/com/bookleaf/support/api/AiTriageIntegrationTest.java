package com.bookleaf.support.api;

import com.bookleaf.support.ai.LlmClient;
import com.bookleaf.support.domain.AiEvent;
import com.bookleaf.support.domain.AiOutcome;
import com.bookleaf.support.domain.Priority;
import com.bookleaf.support.domain.Ticket;
import com.bookleaf.support.domain.TicketCategory;
import com.bookleaf.support.testsupport.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Automatic classification and priority (PRD FR-07; TDD section 7): what is sent to the model, how
 * the answer is validated, and that every kind of AI failure still leaves a working ticket.
 */
class AiTriageIntegrationTest extends IntegrationTestBase {

    private static final String ROYALTY_TEXT = "I have not received any royalty for 6 months. Please tell me what is going on.";

    private List<AiEvent> events() {
        return aiEvents.findAll();
    }

    // ---- a good answer ----

    @Test
    void aValidAnswerIsStoredAsTheSuggestionAndBecomesTheStartingValue() throws Exception {
        llm.respondWith("{\"category\":\"ISBN & Metadata Issues\",\"priority\":\"High\",\"reason\":\"Reported ISBN mismatch.\"}");
        Ticket ticket = awaitTriage(createTicket(authorToken(0), null, "ISBN problem", "The ISBN on Amazon differs from my copy."));

        assertThat(ticket.getAiOutcome()).isEqualTo(AiOutcome.SUCCESS);
        assertThat(ticket.getAiCategory()).isEqualTo(TicketCategory.ISBN_METADATA);
        assertThat(ticket.getAiPriority()).isEqualTo(Priority.HIGH);
        assertThat(ticket.getCategory()).isEqualTo(TicketCategory.ISBN_METADATA);
        assertThat(ticket.getPriority()).isEqualTo(Priority.HIGH);
        assertThat(ticket.getAiReason()).isEqualTo("Reported ISBN mismatch.");

        assertThat(events()).singleElement().satisfies(event -> {
            assertThat(event.getTaskType()).isEqualTo(AiEvent.Task.TRIAGE);
            assertThat(event.getOutcome()).isEqualTo(AiEvent.Outcome.SUCCESS);
            assertThat(event.getProvider()).isEqualTo("test-provider");
            assertThat(event.getModel()).isEqualTo("test-model");
            assertThat(event.getInputTokens()).isEqualTo(210);
            assertThat(event.getOutputTokens()).isEqualTo(40);
            assertThat(event.getLatencyMs()).isEqualTo(25L);
            assertThat(event.getFailureReason()).isNull();
        });
    }

    @Test
    void triageSendsOnlyTheTicketTextAndTheBookStatusInASmallPrompt() throws Exception {
        llm.respondWith("{\"category\":\"Royalty & Payments\",\"priority\":\"High\",\"reason\":\"x\"}");
        String token = authorToken(0);
        String bookId = bookId(token, author(0).get("books").get(0).get("book_id").asString());
        awaitTriage(createTicket(token, bookId, "Royalty not received", ROYALTY_TEXT));

        assertThat(llm.requests()).hasSize(1);
        LlmClient.LlmRequest request = llm.requests().get(0);
        assertThat(request.jsonMode()).isTrue();
        assertThat(request.temperature()).isZero();
        assertThat(request.system()).contains("valid JSON").contains("Royalty & Payments")
                .doesNotContain("PRODUCTION STAGES").doesNotContain("RESPONSE PATTERNS");
        assertThat(request.user()).contains("Royalty not received").contains(ROYALTY_TEXT)
                .contains(author(0).get("books").get(0).get("title").asString())
                .doesNotContain("@").doesNotContain(author(0).get("phone").asString())
                .doesNotContain(author(0).get("name").asString());
    }

    @Test
    void triageRunsExactlyOncePerTicketAndNeverForAdminActions() throws Exception {
        llm.respondWith("{\"category\":\"General Inquiry\",\"priority\":\"Low\",\"reason\":\"x\"}");
        String id = createTicket(authorToken(0), null, "Hello", "Just saying hello to the team here.");
        awaitTriage(id);
        String admin = adminToken();
        get(admin, "/api/v1/admin/tickets").andExpect(status().isOk());
        get(admin, "/api/v1/admin/tickets/" + id).andExpect(status().isOk());
        patch(admin, "/api/v1/admin/tickets/" + id, Map.of("status", "IN_PROGRESS")).andExpect(status().isOk());
        post(admin, "/api/v1/admin/tickets/" + id + "/replies", Map.of("body", "Hello back.")).andExpect(status().isCreated());
        get(authorToken(0), "/api/v1/author/tickets/" + id).andExpect(status().isOk());

        assertThat(llm.requests()).hasSize(1);
    }

    // ---- unusable answers ----

    @ParameterizedTest
    @ValueSource(strings = {
            "Sure! This looks like a royalty question.",
            "",
            "[]",
            "{\"category\":\"Billing\",\"priority\":\"High\",\"reason\":\"x\"}",
            "{\"category\":\"Royalty & Payments\",\"priority\":\"Urgent\",\"reason\":\"x\"}",
            "{\"category\":\"Royalty & Payments\",\"priority\":\"High\"}",
            "{\"category\":\"Royalty & Payments\",\"priority\":\"High\",\"reason\":\"x\",\"confidence\":0.9}",
            "{\"category\":5,\"priority\":\"High\",\"reason\":\"x\"}"
    })
    void unusableAnswersFallBackToTheRulesAndAreRecorded(String answer) throws Exception {
        llm.respondWith(answer.isEmpty() ? " " : answer);
        Ticket ticket = awaitTriage(createTicket(authorToken(0), null, "Royalty not received", ROYALTY_TEXT));

        assertThat(ticket.getAiOutcome()).isEqualTo(AiOutcome.FALLBACK);
        assertThat(ticket.getAiCategory()).isNull();
        assertThat(ticket.getAiPriority()).isNull();
        assertThat(ticket.getCategory()).isEqualTo(TicketCategory.ROYALTY_PAYMENTS);   // from the rule-based fallback
        assertThat(ticket.getPriority()).isEqualTo(Priority.HIGH);
        assertThat(events()).singleElement().satisfies(event -> {
            assertThat(event.getOutcome()).isEqualTo(AiEvent.Outcome.FALLBACK);
            assertThat(event.getFailureReason()).isIn("invalid_output", "empty_response");
        });
    }

    // ---- the AI being down ----

    @ParameterizedTest
    @ValueSource(strings = {"rate_limited", "timeout_or_network", "provider_error_500", "auth_failed", "not_configured"})
    void aProviderFailureNeverBlocksTicketCreationOrLeavesItUnclassified(String reason) throws Exception {
        llm.failWith(reason);
        String token = authorToken(0);

        String id = read(post(token, "/api/v1/author/tickets", Map.of("subject", "Royalty not received", "description", ROYALTY_TEXT))
                .andExpect(status().isCreated()), "$.id");
        get(token, "/api/v1/author/tickets/" + id).andExpect(status().isOk());   // visible straight away

        Ticket ticket = awaitTriage(id);
        assertThat(ticket.getAiOutcome()).isEqualTo(AiOutcome.FALLBACK);
        assertThat(ticket.getCategory()).isEqualTo(TicketCategory.ROYALTY_PAYMENTS);
        assertThat(ticket.getPriority()).isEqualTo(Priority.HIGH);
        assertThat(ticket.getAiReason()).containsIgnoringCase("manual review");
        assertThat(events()).singleElement().satisfies(event -> {
            assertThat(event.getOutcome()).isEqualTo(AiEvent.Outcome.FALLBACK);
            assertThat(event.getFailureReason()).isEqualTo(reason);
            assertThat(event.getInputTokens()).isNull();   // a failed call has no token counts
        });
        // the admin can see it and is told the AI did not decide
        get(adminToken(), "/api/v1/admin/tickets/" + id).andExpect(status().isOk());
    }

    @Test
    void theFallbackFollowsTheSampleQueriesFromTheBrief() throws Exception {
        llm.failWith("not_configured");
        String token = authorToken(0);
        Map<String, List<Object>> expectations = Map.of(
                "My book is showing a different ISBN on Amazon than what is on the physical copy.", List.of(TicketCategory.ISBN_METADATA, Priority.HIGH),
                "I received my author copies and the print quality is terrible. Images are blurry and pages are misaligned.", List.of(TicketCategory.PRINTING_QUALITY, Priority.HIGH),
                "My book is published but it is showing as Currently Unavailable on Amazon.", List.of(TicketCategory.DISTRIBUTION_AVAILABILITY, Priority.MEDIUM),
                "It has been 3 weeks and my book is still in typesetting. When will it be done?", List.of(TicketCategory.BOOK_STATUS_PRODUCTION, Priority.MEDIUM),
                "Can I update my author bio?", List.of(TicketCategory.GENERAL_INQUIRY, Priority.LOW));
        for (Map.Entry<String, List<Object>> entry : expectations.entrySet()) {
            Ticket ticket = awaitTriage(createTicket(token, null, "Question", entry.getKey()));
            assertThat(ticket.getCategory()).as(entry.getKey()).isEqualTo(entry.getValue().get(0));
            assertThat(ticket.getPriority()).as(entry.getKey()).isEqualTo(entry.getValue().get(1));
        }
    }

    // ---- admin overrides ----

    @Test
    void anAdminOverrideSurvivesAndTheAiSuggestionIsKeptSeparately() throws Exception {
        llm.respondWith("{\"category\":\"Royalty & Payments\",\"priority\":\"High\",\"reason\":\"No payout for months.\"}");
        String id = createTicket(authorToken(0), null, "Royalty not received", ROYALTY_TEXT);
        Ticket ticket = awaitTriage(id);
        assertThat(ticket.getAiOutcome()).isEqualTo(AiOutcome.SUCCESS);

        patch(adminToken(), "/api/v1/admin/tickets/" + id, Map.of("category", "GENERAL_INQUIRY", "priority", "LOW")).andExpect(status().isOk());

        Ticket after = tickets.findById(UUID.fromString(id)).orElseThrow();
        assertThat(after.getCategory()).isEqualTo(TicketCategory.GENERAL_INQUIRY);
        assertThat(after.getPriority()).isEqualTo(Priority.LOW);
        assertThat(after.getAiCategory()).isEqualTo(TicketCategory.ROYALTY_PAYMENTS);   // the suggestion is preserved
        assertThat(after.getAiPriority()).isEqualTo(Priority.HIGH);
    }

    @Test
    void aSlowAiAnswerNeverOverwritesWhatAnAdminAlreadyChose() throws Exception {
        llm.respondWith("{\"category\":\"Royalty & Payments\",\"priority\":\"Critical\",\"reason\":\"Late answer.\"}");
        llm.holdResponses();   // the AI is "still thinking"
        String id = createTicket(authorToken(0), null, "Royalty not received", ROYALTY_TEXT);

        // the admin acts while triage is still pending
        patch(adminToken(), "/api/v1/admin/tickets/" + id, Map.of("category", "GENERAL_INQUIRY", "priority", "LOW")).andExpect(status().isOk());
        llm.release();

        Ticket ticket = awaitTriage(id);
        assertThat(ticket.getAiOutcome()).isEqualTo(AiOutcome.SUCCESS);
        assertThat(ticket.getAiCategory()).isEqualTo(TicketCategory.ROYALTY_PAYMENTS);   // recorded as a suggestion
        assertThat(ticket.getAiPriority()).isEqualTo(Priority.CRITICAL);
        assertThat(ticket.getCategory()).isEqualTo(TicketCategory.GENERAL_INQUIRY);      // the admin's choice stands
        assertThat(ticket.getPriority()).isEqualTo(Priority.LOW);
    }

    @Test
    void ticketCreationDoesNotWaitForTheAi() throws Exception {
        llm.respondWith("{\"category\":\"General Inquiry\",\"priority\":\"Low\",\"reason\":\"x\"}");
        llm.holdResponses();
        long started = System.nanoTime();
        String id = createTicket(authorToken(0), null, "Hello", "Just saying hello to the team here.");
        long millis = (System.nanoTime() - started) / 1_000_000;

        assertThat(millis).as("the request returned while the AI was still held back").isLessThan(10_000);
        assertThat(tickets.findById(UUID.fromString(id)).orElseThrow().getAiOutcome()).isEqualTo(AiOutcome.PENDING);
        get(adminToken(), "/api/v1/admin/tickets/" + id).andExpect(status().isOk());   // already workable by the team
        llm.release();
        awaitTriage(id);
        assertThat(HttpStatus.valueOf(200).is2xxSuccessful()).isTrue();
    }
}
