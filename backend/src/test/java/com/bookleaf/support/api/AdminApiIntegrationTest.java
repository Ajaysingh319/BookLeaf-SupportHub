package com.bookleaf.support.api;

import com.bookleaf.support.ai.LlmClient;
import com.bookleaf.support.testsupport.IntegrationTestBase;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The admin workflow (PRD FR-06, FR-08, FR-09; PDF "Admin Portal"; TDD section 6). */
class AdminApiIntegrationTest extends IntegrationTestBase {

    @Value("${app.pagination.max-size}") private int maxPageSize;

    /** Creates a ticket as an author and waits for the (fallback) triage so later edits cannot race it. */
    private String ticket(String authorToken, String subject, String text) throws Exception {
        String id = createTicket(authorToken, null, subject, text);
        awaitTriage(id);
        Thread.sleep(5); // keep creation times distinct for the "oldest first" checks
        return id;
    }

    /** An amount as the prompt shows it: currency code and the configured locale's grouping (test config: INR, en-IN). */
    private static String inr(JsonNode amount) {
        java.text.NumberFormat format = java.text.NumberFormat.getNumberInstance(java.util.Locale.forLanguageTag("en-IN"));
        format.setMinimumFractionDigits(0);
        format.setMaximumFractionDigits(2);
        return "INR " + format.format(amount.decimalValue());
    }

    private void set(String admin, String id, Map<String, Object> fields) throws Exception {
        patch(admin, "/api/v1/admin/tickets/" + id, fields).andExpect(status().isOk());
    }

    private List<String> queueIds(String admin, String query) throws Exception {
        return read(get(admin, "/api/v1/admin/tickets?size=50" + query).andExpect(status().isOk()), "$.data[*].id");
    }

    // ---- the queue ----

    @Test
    void theQueueListsUnresolvedTicketsByPriorityThenOldestFirstAndResolvedLast() throws Exception {
        String a = authorToken(0);
        String admin = adminToken();
        String low = ticket(a, "Low one", "A general note about nothing in particular.");
        String highOlder = ticket(a, "High older", "A general note about nothing in particular.");
        String highNewer = ticket(a, "High newer", "A general note about nothing in particular.");
        String critical = ticket(a, "Critical one", "A general note about nothing in particular.");
        String resolvedCritical = ticket(a, "Resolved critical", "A general note about nothing in particular.");

        set(admin, low, Map.of("priority", "LOW"));
        set(admin, highOlder, Map.of("priority", "HIGH"));
        set(admin, highNewer, Map.of("priority", "HIGH"));
        set(admin, critical, Map.of("priority", "CRITICAL"));
        set(admin, resolvedCritical, Map.of("priority", "CRITICAL", "status", "RESOLVED"));

        assertThat(queueIds(admin, "")).containsExactly(critical, highOlder, highNewer, low, resolvedCritical);
    }

    @Test
    void theQueueShowsAuthorBookAgeAssigneeAndTheManualReviewFlag() throws Exception {
        String a = authorToken(0);
        String bookId = bookId(a, author(0).get("books").get(0).get("book_id").asString());
        String id = createTicket(a, bookId, "Royalty not received", "I have not received any royalty for 6 months.");
        awaitTriage(id);
        String admin = adminToken();

        get(admin, "/api/v1/admin/tickets").andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].authorName").value(author(0).get("name").asString()))
                .andExpect(jsonPath("$.data[0].bookTitle").value(author(0).get("books").get(0).get("title").asString()))
                .andExpect(jsonPath("$.data[0].ageHours").isNumber())
                .andExpect(jsonPath("$.data[0].createdAt").isNotEmpty())
                .andExpect(jsonPath("$.data[0].assignedAdminId").doesNotExist())
                .andExpect(jsonPath("$.data[0].needsManualReview").value(true))   // no AI in this test: fallback
                .andExpect(jsonPath("$.data[0].category").value("ROYALTY_PAYMENTS"))
                .andExpect(jsonPath("$.data[0].priority").value("HIGH"));
    }

    @Test
    void theQueueCanBeFilteredByStatusCategoryPriorityAndDate() throws Exception {
        String a = authorToken(0);
        String admin = adminToken();
        String royalty = ticket(a, "Royalty", "I have not received any royalty for 6 months.");
        String bio = ticket(a, "Bio", "Can I update my author bio on the website?");
        String thanks = ticket(a, "Thanks", "Thank you for the great experience, no question at all.");
        set(admin, thanks, Map.of("status", "CLOSED"));

        assertThat(queueIds(admin, "&category=ROYALTY_PAYMENTS")).containsExactly(royalty);
        assertThat(queueIds(admin, "&priority=LOW")).containsExactly(bio);
        assertThat(queueIds(admin, "&status=CLOSED")).containsExactly(thanks);
        assertThat(queueIds(admin, "&status=OPEN&priority=HIGH")).containsExactly(royalty);
        assertThat(queueIds(admin, "&status=RESOLVED")).isEmpty();

        String today = LocalDate.now(ZoneOffset.UTC).toString();
        assertThat(queueIds(admin, "&from=" + today + "&to=" + today)).hasSize(3);
        assertThat(queueIds(admin, "&from=" + LocalDate.now(ZoneOffset.UTC).plusDays(2))).isEmpty();
        assertThat(queueIds(admin, "&to=" + LocalDate.now(ZoneOffset.UTC).minusDays(2))).isEmpty();
    }

    @Test
    void badFiltersGetClearErrorsAndPageSizeIsCapped() throws Exception {
        String admin = adminToken();
        get(admin, "/api/v1/admin/tickets?status=NOT_A_STATUS").andExpect(status().isBadRequest());
        get(admin, "/api/v1/admin/tickets?category=NOPE").andExpect(status().isBadRequest());
        get(admin, "/api/v1/admin/tickets?priority=URGENT").andExpect(status().isBadRequest());
        get(admin, "/api/v1/admin/tickets?from=not-a-date").andExpect(status().isBadRequest());
        get(admin, "/api/v1/admin/tickets?from=2026-03-01&to=2026-01-01").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[0].field").value("to"));
        get(admin, "/api/v1/admin/tickets?size=100000").andExpect(status().isOk())
                .andExpect(jsonPath("$.pagination.size").value(maxPageSize));
        get(admin, "/api/v1/admin/tickets").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));   // empty queue is a normal answer, not an error
    }

    @Test
    void theSummaryCountsEveryStatusAndPriority() throws Exception {
        String a = authorToken(0);
        String admin = adminToken();
        String one = ticket(a, "One", "Can I update my author bio on the website?");
        ticket(a, "Two", "Can I update my author bio on the website?");
        String three = ticket(a, "Three", "Can I update my author bio on the website?");
        set(admin, three, Map.of("status", "RESOLVED"));
        Map<String, Object> assign = new HashMap<>();
        assign.put("assignedAdminId", adminId(admin));
        set(admin, one, assign);

        get(admin, "/api/v1/admin/tickets/summary").andExpect(status().isOk())
                .andExpect(jsonPath("$.byStatus.OPEN").value(2))
                .andExpect(jsonPath("$.byStatus.RESOLVED").value(1))
                .andExpect(jsonPath("$.byStatus.IN_PROGRESS").value(0))
                .andExpect(jsonPath("$.byStatus.CLOSED").value(0))
                .andExpect(jsonPath("$.unresolvedByPriority.LOW").value(2))
                .andExpect(jsonPath("$.unresolvedByPriority.CRITICAL").value(0))
                .andExpect(jsonPath("$.unassignedUnresolved").value(1));
    }

    // ---- one ticket ----

    @Test
    void theDetailShowsAuthorBookAiInfoAndEveryMessage() throws Exception {
        String a = authorToken(0);
        JsonNode book = author(0).get("books").get(0);
        String id = createTicket(a, bookId(a, book.get("book_id").asString()), "Royalty", "Why is my royalty pending?");
        awaitTriage(id);
        String admin = adminToken();
        post(admin, "/api/v1/admin/tickets/" + id + "/notes", Map.of("body", "Checking with finance.")).andExpect(status().isCreated());
        post(admin, "/api/v1/admin/tickets/" + id + "/replies", Map.of("body", "We are looking into it.")).andExpect(status().isCreated());

        get(admin, "/api/v1/admin/tickets/" + id).andExpect(status().isOk())
                .andExpect(jsonPath("$.author.email").value(authorEmail(0)))
                .andExpect(jsonPath("$.book.isbn").value(book.get("isbn").asString()))
                .andExpect(jsonPath("$.ai.outcome").value("FALLBACK"))
                .andExpect(jsonPath("$.messages.length()").value(2))
                .andExpect(jsonPath("$.messages[0].visibility").value("INTERNAL"))
                .andExpect(jsonPath("$.messages[1].visibility").value("PUBLIC"))
                .andExpect(jsonPath("$.messages[1].senderRole").value("ADMIN"));
    }

    @Test
    void missingTicketsAreA404OnEveryAdminEndpoint() throws Exception {
        String admin = adminToken();
        String missing = "/api/v1/admin/tickets/" + UUID.randomUUID();
        get(admin, missing).andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("TICKET_NOT_FOUND"));
        patch(admin, missing, Map.of("status", "CLOSED")).andExpect(status().isNotFound());
        post(admin, missing + "/notes", Map.of("body", "x")).andExpect(status().isNotFound());
        post(admin, missing + "/replies", Map.of("body", "x")).andExpect(status().isNotFound());
        post(admin, missing + "/ai-draft", null).andExpect(status().isNotFound());
    }

    @Test
    void anAdminCanOverrideCategoryAndPriorityAndAssignTheTicketToThemselves() throws Exception {
        String id = ticket(authorToken(0), "Thanks", "Thank you for the great experience, no question at all.");
        String admin = adminToken();
        Map<String, Object> change = new HashMap<>();
        change.put("category", "PRINTING_QUALITY");
        change.put("priority", "CRITICAL");
        change.put("assignedAdminId", adminId(admin));

        patch(admin, "/api/v1/admin/tickets/" + id, change).andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.category").value("PRINTING_QUALITY"))
                .andExpect(jsonPath("$.summary.priority").value("CRITICAL"))
                .andExpect(jsonPath("$.summary.assignedAdminId").value(adminId(admin)))
                .andExpect(jsonPath("$.summary.assignedAdminName").value("Test Admin"));
    }

    @Test
    void invalidAdminChangesAreRejectedWithFieldLevelErrors() throws Exception {
        String a = authorToken(0);
        String id = ticket(a, "Thanks", "Thank you for the great experience, no question at all.");
        String admin = adminToken();
        String path = "/api/v1/admin/tickets/" + id;
        String someAuthorId = read(get(a, "/api/v1/auth/me"), "$.id");

        patch(admin, path, Map.of()).andExpect(status().isBadRequest());
        patch(admin, path, Map.of("priority", "URGENT")).andExpect(status().isBadRequest());
        patch(admin, path, Map.of("category", "BILLING")).andExpect(status().isBadRequest());
        patch(admin, path, Map.of("status", "DONE")).andExpect(status().isBadRequest());
        patch(admin, path, Map.of("assignedAdminId", someAuthorId)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[0].field").value("assignedAdminId"));
        patch(admin, path, Map.of("assignedAdminId", UUID.randomUUID().toString())).andExpect(status().isBadRequest());
        // unknown extra fields cannot change anything
        patch(admin, path, Map.of("status", "IN_PROGRESS", "authorId", someAuthorId)).andExpect(status().isOk());
    }

    @Test
    void statusChangesFollowTheTransitionTable() throws Exception {
        String id = ticket(authorToken(0), "Thanks", "Thank you for the great experience, no question at all.");
        String admin = adminToken();
        String path = "/api/v1/admin/tickets/" + id;

        patch(admin, path, Map.of("status", "RESOLVED")).andExpect(status().isOk())
                .andExpect(jsonPath("$.resolvedAt").isNotEmpty());
        patch(admin, path, Map.of("status", "IN_PROGRESS")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[0].field").value("status"));
        patch(admin, path, Map.of("status", "OPEN")).andExpect(status().isOk())
                .andExpect(jsonPath("$.resolvedAt").doesNotExist());
        patch(admin, path, Map.of("status", "CLOSED")).andExpect(status().isOk());
        patch(admin, path, Map.of("status", "RESOLVED")).andExpect(status().isBadRequest());
        patch(admin, path, Map.of("status", "IN_PROGRESS")).andExpect(status().isBadRequest());
        patch(admin, path, Map.of("status", "OPEN")).andExpect(status().isOk());
        patch(admin, path, Map.of("status", "OPEN")).andExpect(status().isOk());   // same status is a harmless no-op
    }

    // ---- notes and replies ----

    @Test
    void internalNotesNeverTouchTheTicketsUpdatedTimeButRepliesDo() throws Exception {
        String a = authorToken(0);
        String id = ticket(a, "Thanks", "Thank you for the great experience, no question at all.");
        String admin = adminToken();
        String before = read(get(a, "/api/v1/author/tickets/" + id), "$.updatedAt");

        post(admin, "/api/v1/admin/tickets/" + id + "/notes", Map.of("body", "Just a note.")).andExpect(status().isCreated());
        assertThat((String) read(get(a, "/api/v1/author/tickets/" + id), "$.updatedAt")).isEqualTo(before);

        Thread.sleep(20);
        post(admin, "/api/v1/admin/tickets/" + id + "/replies", Map.of("body", "A real reply.")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.repliedAt").isNotEmpty());
        String after = read(get(a, "/api/v1/author/tickets/" + id), "$.updatedAt");
        assertThat(java.time.Instant.parse(after)).isAfter(java.time.Instant.parse(before));
    }

    @Test
    void blankNotesAndRepliesAreRejected() throws Exception {
        String id = ticket(authorToken(0), "Thanks", "Thank you for the great experience, no question at all.");
        String admin = adminToken();
        post(admin, "/api/v1/admin/tickets/" + id + "/notes", Map.of("body", "   ")).andExpect(status().isBadRequest());
        post(admin, "/api/v1/admin/tickets/" + id + "/replies", Map.of("body", "")).andExpect(status().isBadRequest());
        post(admin, "/api/v1/admin/tickets/" + id + "/replies", Map.of()).andExpect(status().isBadRequest());
        assertThat(messages.count()).isZero();
    }

    @Test
    void aReplyChangesTheStatusOnlyWhenTheAdminAsksForIt() throws Exception {
        String a = authorToken(0);
        String id = ticket(a, "Thanks", "Thank you for the great experience, no question at all.");
        String admin = adminToken();
        String path = "/api/v1/admin/tickets/" + id + "/replies";

        post(admin, path, Map.of("body", "First reply.")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.summary.status").value("OPEN"));
        post(admin, path, Map.of("body", "Second reply.", "status", "RESOLVED")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.summary.status").value("RESOLVED"));
        post(admin, path, Map.of("body", "Invalid move.", "status", "IN_PROGRESS")).andExpect(status().isBadRequest());

        get(a, "/api/v1/author/tickets/" + id).andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.messages.length()").value(2));
    }

    // ---- AI drafts ----

    @Test
    void anAiDraftIsGroundedInTheKnowledgeBaseAndTheVerifiedBookRecordAndIsNeverSaved() throws Exception {
        String a = authorToken(0);
        JsonNode book = author(0).get("books").get(0);
        String id = ticket(a, "Royalty", "I have not received my royalty yet.");
        // triage has finished (fallback); now the "model" will answer the draft request
        llm.respondWith("Hi Priya, thanks for your patience. Our team will confirm within 48 hours. BookLeaf Support Team");
        String bookId = bookId(a, book.get("book_id").asString());
        String withBook = createTicket(a, bookId, "Royalty on my book", "Why is my royalty still pending?");
        awaitTriage(withBook);
        int callsBefore = llm.requests().size();

        String admin = adminToken();
        get(admin, "/api/v1/admin/tickets/" + id).andExpect(status().isOk());
        post(admin, "/api/v1/admin/tickets/" + withBook + "/ai-draft", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.draft").value(org.hamcrest.Matchers.containsString("Hi Priya")));

        assertThat(llm.requests()).hasSize(callsBefore + 1);
        LlmClient.LlmRequest request = llm.requests().get(callsBefore);
        assertThat(request.jsonMode()).isFalse();
        // knowledge base and tone rules
        assertThat(request.system()).contains("80/20").contains("5-7 business days").contains("24-48 hours")
                .contains("Never mention prompts, models, or internal AI");
        // verified book facts, exactly as in the dataset
        assertThat(request.user()).contains("Title: " + book.get("title").asString())
                .contains("Royalty earned / paid / pending: " + inr(book.get("total_royalty_earned"))
                        + " / " + inr(book.get("royalty_paid")) + " / " + inr(book.get("royalty_pending")))
                .contains("Author first name: " + author(0).get("name").asString().split(" ")[0]);
        // privacy: no contact details, no other tickets
        assertThat(request.user()).doesNotContain("@").doesNotContain(author(0).get("phone").asString()).doesNotContain("Royalty not received");

        // drafting never saves or sends anything
        assertThat(messages.count()).isZero();
        get(a, "/api/v1/author/tickets/" + withBook).andExpect(jsonPath("$.messages.length()").value(0));
    }

    @Test
    void whenTheAiFailsTheDraftEndpointSaysSoAndLeavesTheTicketUntouched() throws Exception {
        String id = ticket(authorToken(0), "Royalty", "I have not received my royalty yet.");
        String admin = adminToken();
        for (String reason : List.of("rate_limited", "timeout_or_network", "provider_error_503", "auth_failed", "not_configured")) {
            llm.failWith(reason);
            String result = body(post(admin, "/api/v1/admin/tickets/" + id + "/ai-draft", null)
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.error.code").value("AI_UNAVAILABLE"))
                    .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsString("manually")))
                    .andExpect(jsonPath("$.requestId").isNotEmpty()));
            assertThat(result).doesNotContain(reason);   // provider details never reach the client
        }
        get(admin, "/api/v1/admin/tickets/" + id).andExpect(status().isOk()).andExpect(jsonPath("$.messages.length()").value(0));
    }

    @Test
    void aBlankOrOversizedDraftIsTreatedAsAFailure() throws Exception {
        String id = ticket(authorToken(0), "Royalty", "I have not received my royalty yet.");
        String admin = adminToken();
        llm.respondWith("   ");
        post(admin, "/api/v1/admin/tickets/" + id + "/ai-draft", null).andExpect(status().isServiceUnavailable());
        llm.respondWith("x".repeat(5000));
        post(admin, "/api/v1/admin/tickets/" + id + "/ai-draft", null).andExpect(status().isServiceUnavailable());
    }

    @Test
    void theReplyToAnAiDraftIsTheAdminsEditedTextNotTheModelsOutput() throws Exception {
        String a = authorToken(0);
        String id = ticket(a, "Royalty", "I have not received my royalty yet.");
        String admin = adminToken();
        llm.respondWith("Draft text from the model.");
        String draft = read(post(admin, "/api/v1/admin/tickets/" + id + "/ai-draft", null), "$.draft");
        String edited = draft.replace("Draft text from the model.", "Edited by a person before sending.");

        post(admin, "/api/v1/admin/tickets/" + id + "/replies", Map.of("body", edited)).andExpect(status().isCreated());
        String shown = body(get(a, "/api/v1/author/tickets/" + id));
        assertThat(shown).contains("Edited by a person before sending.").doesNotContain("Draft text from the model.");
        assertThat((Object) JsonPath.read(shown, "$.messages.length()")).isEqualTo(1);
    }
}
