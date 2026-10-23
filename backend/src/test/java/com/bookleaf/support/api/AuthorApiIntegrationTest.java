package com.bookleaf.support.api;

import com.bookleaf.support.dto.ValidationLimits;
import com.bookleaf.support.testsupport.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** What an author can see and do (PRD FR-02 to FR-05, FR-11; PDF "Author Portal"). */
class AuthorApiIntegrationTest extends IntegrationTestBase {

    @Value("${app.pagination.max-size}") private int maxPageSize;

    private static BigDecimal decimal(Object number) {
        return number == null ? null : new BigDecimal(number.toString());
    }

    private static void assertSameAmount(Object actual, JsonNode expected) {
        if (expected == null || expected.isNull()) {
            assertThat(actual).isNull();
        } else {
            assertThat(decimal(actual)).isEqualByComparingTo(expected.decimalValue());
        }
    }

    // ---- books ----

    @Test
    void everyAuthorSeesExactlyTheirOwnBooksWithTheDatasetFigures() throws Exception {
        for (int i = 0; i < dataset.get("authors").size(); i++) {
            JsonNode expectedAuthor = author(i);
            String body = body(get(authorToken(i), "/api/v1/author/books").andExpect(status().isOk()));
            List<String> ids = com.jayway.jsonpath.JsonPath.read(body, "$[*].sourceBookId");
            List<String> expectedIds = new java.util.ArrayList<>();
            expectedAuthor.get("books").forEach(book -> expectedIds.add(book.get("book_id").asString()));
            assertThat(ids).containsExactlyInAnyOrderElementsOf(expectedIds);

            for (JsonNode book : expectedAuthor.get("books")) {
                String base = "$[?(@.sourceBookId=='" + book.get("book_id").asString() + "')]";
                assertSameAmount(((List<?>) com.jayway.jsonpath.JsonPath.read(body, base + ".totalRoyaltyEarned")).get(0), book.get("total_royalty_earned"));
                assertSameAmount(((List<?>) com.jayway.jsonpath.JsonPath.read(body, base + ".royaltyPaid")).get(0), book.get("royalty_paid"));
                assertSameAmount(((List<?>) com.jayway.jsonpath.JsonPath.read(body, base + ".royaltyPending")).get(0), book.get("royalty_pending"));
                assertSameAmount(((List<?>) com.jayway.jsonpath.JsonPath.read(body, base + ".mrp")).get(0), book.get("mrp"));
            }
        }
    }

    @Test
    void booksStillInProductionKeepNullsAndZerosInsteadOfBreaking() throws Exception {
        for (int i = 0; i < dataset.get("authors").size(); i++) {
            for (JsonNode book : author(i).get("books")) {
                if (!book.get("mrp").isNull()) continue;
                String base = "$[?(@.sourceBookId=='" + book.get("book_id").asString() + "')]";
                String body = body(get(authorToken(i), "/api/v1/author/books"));
                assertThat(((List<?>) com.jayway.jsonpath.JsonPath.read(body, base + ".mrp")).get(0)).isNull();
                assertThat(((List<?>) com.jayway.jsonpath.JsonPath.read(body, base + ".publicationDate")).get(0)).isNull();
                assertThat(((List<?>) com.jayway.jsonpath.JsonPath.read(body, base + ".printPartner")).get(0)).isNull();
                assertThat(decimal(((List<?>) com.jayway.jsonpath.JsonPath.read(body, base + ".royaltyPending")).get(0))).isEqualByComparingTo("0");
                assertThat((List<?>) com.jayway.jsonpath.JsonPath.read(body, base + ".availableOn[0]")).isEmpty();
                return;
            }
        }
        throw new AssertionError("The dataset has no book in production to test with");
    }

    // ---- creating tickets ----

    @Test
    void anAuthorCanRaiseATicketOnAnOwnBookOrAtAccountLevel() throws Exception {
        String token = authorToken(0);
        String bookId = bookId(token, author(0).get("books").get(0).get("book_id").asString());

        post(token, "/api/v1/author/tickets", Map.of("bookId", bookId, "subject", "Royalty question", "description", "Why is my royalty still pending?"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.bookId").value(bookId))
                .andExpect(jsonPath("$.messages.length()").value(0));

        post(token, "/api/v1/author/tickets", Map.of("subject", "Update my bio", "description", "Please update my author bio."))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.bookId").doesNotExist())
                .andExpect(jsonPath("$.bookTitle").doesNotExist());
    }

    @Test
    void anAuthorCannotRaiseATicketOnAnotherAuthorsBook() throws Exception {
        String other = authorToken(1);
        String othersBook = bookId(other, author(1).get("books").get(0).get("book_id").asString());

        post(authorToken(0), "/api/v1/author/tickets", Map.of("bookId", othersBook, "subject", "Not mine", "description", "Trying someone else's book."))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("bookId"));
        assertThat(tickets.count()).isZero();

        post(authorToken(0), "/api/v1/author/tickets", Map.of("bookId", UUID.randomUUID().toString(), "subject", "No such book", "description", "A book id that does not exist."))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[0].field").value("bookId"));
    }

    @Test
    void inputIsValidatedAgainstTheDocumentedLimits() throws Exception {
        String token = authorToken(0);
        String ok = "A long enough description for this test.";

        post(token, "/api/v1/author/tickets", Map.of("subject", "x".repeat(ValidationLimits.SUBJECT_MIN - 1), "description", ok))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.details[0].field").value("subject"));
        post(token, "/api/v1/author/tickets", Map.of("subject", "x".repeat(ValidationLimits.SUBJECT_MAX + 1), "description", ok))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.details[0].field").value("subject"));
        post(token, "/api/v1/author/tickets", Map.of("subject", "Valid subject", "description", "x".repeat(ValidationLimits.DESCRIPTION_MIN - 1)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.details[0].field").value("description"));
        post(token, "/api/v1/author/tickets", Map.of("subject", "Valid subject", "description", "x".repeat(ValidationLimits.DESCRIPTION_MAX + 1)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.details[0].field").value("description"));
        post(token, "/api/v1/author/tickets", Map.of("subject", "   ", "description", "          "))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.details.length()").value(2));
        // exactly at the limits is accepted
        post(token, "/api/v1/author/tickets", Map.of("subject", "x".repeat(ValidationLimits.SUBJECT_MAX), "description", "y".repeat(ValidationLimits.DESCRIPTION_MAX)))
                .andExpect(status().isCreated());
        assertThat(tickets.count()).isEqualTo(1);
    }

    @Test
    void malformedJsonGetsAClearErrorNotA500() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/author/tickets")
                        .header("Authorization", "Bearer " + authorToken(0))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST_BODY"));
    }

    // ---- listing and reading tickets ----

    @Test
    void ticketListsAreOwnOnlyPaginatedAndCapped() throws Exception {
        String a = authorToken(0);
        String b = authorToken(1);
        createTicket(a, null, "First ticket", "The first ticket of author A.");
        createTicket(a, null, "Second ticket", "The second ticket of author A.");
        createTicket(a, null, "Third ticket", "The third ticket of author A.");
        createTicket(b, null, "Other author's ticket", "A ticket that belongs to author B.");

        get(a, "/api/v1/author/tickets?page=0&size=2").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.pagination.totalItems").value(3))
                .andExpect(jsonPath("$.pagination.totalPages").value(2))
                .andExpect(jsonPath("$.data[0].subject").value("Third ticket"));
        get(a, "/api/v1/author/tickets?page=1&size=2").andExpect(jsonPath("$.data.length()").value(1));
        get(b, "/api/v1/author/tickets").andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].subject").value("Other author's ticket"));
        get(a, "/api/v1/author/tickets?size=100000").andExpect(jsonPath("$.pagination.size").value(maxPageSize));
        assertThat((List<?>) read(get(authorToken(2), "/api/v1/author/tickets"), "$.data")).isEmpty();
    }

    @Test
    void anotherAuthorsTicketLooksExactlyLikeAMissingOne() throws Exception {
        String id = createTicket(authorToken(0), null, "Private query", "Only author A may read this.");

        get(authorToken(1), "/api/v1/author/tickets/" + id).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("TICKET_NOT_FOUND"));
        get(authorToken(1), "/api/v1/author/tickets/" + UUID.randomUUID()).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("TICKET_NOT_FOUND"));
        get(authorToken(0), "/api/v1/author/tickets/" + id).andExpect(status().isOk());
        get(authorToken(0), "/api/v1/author/tickets/not-a-uuid").andExpect(status().isBadRequest());
    }

    @Test
    void authorsNeverSeeInternalNotesOrAdminOnlyFields() throws Exception {
        String authorToken = authorToken(0);
        String id = createTicket(authorToken, null, "Royalty question", "I have not received my royalty for 6 months.");
        awaitTriage(id);

        String admin = adminToken();
        String secretNote = "INTERNAL-NOTE-" + UUID.randomUUID();
        post(admin, "/api/v1/admin/tickets/" + id + "/notes", Map.of("body", secretNote)).andExpect(status().isCreated());
        post(admin, "/api/v1/admin/tickets/" + id + "/replies", Map.of("body", "Hi, we are looking into this.")).andExpect(status().isCreated());
        Map<String, Object> patch = new HashMap<>();
        patch.put("priority", "CRITICAL");
        patch.put("assignedAdminId", adminId(admin));
        patch(admin, "/api/v1/admin/tickets/" + id, patch).andExpect(status().isOk());

        String detail = body(get(authorToken, "/api/v1/author/tickets/" + id).andExpect(status().isOk())
                .andExpect(jsonPath("$.messages.length()").value(1))
                .andExpect(jsonPath("$.messages[0].body").value("Hi, we are looking into this.")));
        assertThat(detail).doesNotContain(secretNote)
                .doesNotContain("INTERNAL")
                .doesNotContain("\"priority\"").doesNotContain("\"category\"")
                .doesNotContainIgnoringCase("aiOutcome").doesNotContainIgnoringCase("assignedAdmin")
                .doesNotContainIgnoringCase("triage");
        assertThat(body(get(authorToken, "/api/v1/author/tickets"))).doesNotContain(secretNote).doesNotContain("\"priority\"");
    }

    @Test
    void aTicketIsSavedAndVisibleToTheAuthorImmediately() throws Exception {
        String token = authorToken(0);
        String id = createTicket(token, null, "Quick question", "Is this saved right away?");
        get(token, "/api/v1/author/tickets/" + id).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.description").value("Is this saved right away?"));
        assertThat(tickets.count()).isEqualTo(1);
        assertThat(tickets.findAll().get(0).getStatus().name()).isEqualTo("OPEN");
    }
}
