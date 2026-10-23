package com.bookleaf.support.api;

import com.bookleaf.support.dto.ValidationLimits;
import com.bookleaf.support.testsupport.IntegrationTestBase;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Author follow-ups, reopen-on-reply and the "awaiting reply" signal (TDD section 8; PRD FR-05). */
class AuthorRepliesIntegrationTest extends IntegrationTestBase {

    private String ticket(String authorToken, String subject) throws Exception {
        String id = createTicket(authorToken, null, subject, "A description that is long enough to be valid.");
        awaitTriage(id);
        Thread.sleep(5);
        return id;
    }

    private String repliesPath(String ticketId) {
        return "/api/v1/author/tickets/" + ticketId + "/replies";
    }

    private List<String> awaitingIds(String admin) throws Exception {
        return read(get(admin, "/api/v1/admin/tickets?size=50&awaiting=true").andExpect(status().isOk()), "$.data[*].id");
    }

    // ---- writing a follow-up ----

    @Test
    void anAuthorCanAddAFollowUpThatBothSidesSeeAsTheAuthorsOwnMessage() throws Exception {
        String a = authorToken(0);
        String id = ticket(a, "Question");
        String admin = adminToken();
        post(admin, "/api/v1/admin/tickets/" + id + "/replies", Map.of("body", "We are looking into it.")).andExpect(status().isCreated());

        post(a, repliesPath(id), Map.of("body", "  Thank you, one more detail: it was the March payout.  ")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.messages.length()").value(2))
                .andExpect(jsonPath("$.messages[1].body").value("Thank you, one more detail: it was the March payout."))
                .andExpect(jsonPath("$.messages[1].senderRole").value("AUTHOR"))
                .andExpect(jsonPath("$.messages[1].senderName").value(author(0).get("name").asString()));

        get(admin, "/api/v1/admin/tickets/" + id)
                .andExpect(jsonPath("$.messages.length()").value(2))
                .andExpect(jsonPath("$.messages[1].senderRole").value("AUTHOR"))
                .andExpect(jsonPath("$.messages[1].visibility").value("PUBLIC"));
    }

    @Test
    void aFollowUpMovesTheTicketsUpdatedTimeButNotItsStatusWhileItIsOpenOrInProgress() throws Exception {
        String a = authorToken(0);
        String id = ticket(a, "Question");
        String admin = adminToken();
        patch(admin, "/api/v1/admin/tickets/" + id, Map.of("status", "IN_PROGRESS")).andExpect(status().isOk());
        String before = read(get(a, "/api/v1/author/tickets/" + id), "$.updatedAt");
        Thread.sleep(20);

        post(a, repliesPath(id), Map.of("body", "Any news?")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));
        String after = read(get(a, "/api/v1/author/tickets/" + id), "$.updatedAt");
        assertThat(Instant.parse(after)).isAfter(Instant.parse(before));
    }

    @Test
    void aFollowUpOnAResolvedTicketReopensItForTheTeam() throws Exception {
        String a = authorToken(0);
        String id = ticket(a, "Question");
        String admin = adminToken();
        post(admin, "/api/v1/admin/tickets/" + id + "/replies", Map.of("body", "All done.", "status", "RESOLVED")).andExpect(status().isCreated());
        get(admin, "/api/v1/admin/tickets/" + id).andExpect(jsonPath("$.resolvedAt").isNotEmpty());

        post(a, repliesPath(id), Map.of("body", "That did not fix it.")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("OPEN"));
        get(admin, "/api/v1/admin/tickets/" + id)
                .andExpect(jsonPath("$.summary.status").value("OPEN"))
                .andExpect(jsonPath("$.resolvedAt").doesNotExist())
                .andExpect(jsonPath("$.summary.awaitingReply").value(true));
    }

    @Test
    void aClosedTicketCannotBeWrittenOnByTheAuthor() throws Exception {
        String a = authorToken(0);
        String id = ticket(a, "Question");
        String admin = adminToken();
        patch(admin, "/api/v1/admin/tickets/" + id, Map.of("status", "CLOSED")).andExpect(status().isOk());

        post(a, repliesPath(id), Map.of("body", "Can I add something?")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("TICKET_CLOSED"))
                .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsString("new ticket")));
        assertThat(messages.count()).isZero();
        get(admin, "/api/v1/admin/tickets/" + id).andExpect(jsonPath("$.summary.status").value("CLOSED"));
        // an admin can still reopen it explicitly, after which the author may write again
        patch(admin, "/api/v1/admin/tickets/" + id, Map.of("status", "OPEN")).andExpect(status().isOk());
        post(a, repliesPath(id), Map.of("body", "Now I can.")).andExpect(status().isCreated());
    }

    // ---- who may do it, and input checks ----

    @Test
    void onlyTheOwnerCanReplyAndOnlyAuthorsCanUseTheEndpoint() throws Exception {
        String id = ticket(authorToken(0), "Private question");

        post(authorToken(1), repliesPath(id), Map.of("body", "I am not the owner.")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("TICKET_NOT_FOUND"));
        post(authorToken(0), repliesPath(UUID.randomUUID().toString()), Map.of("body", "No such ticket.")).andExpect(status().isNotFound());
        post(adminToken(), repliesPath(id), Map.of("body", "Admins use the admin endpoint.")).andExpect(status().isForbidden());
        post(null, repliesPath(id), Map.of("body", "Not signed in.")).andExpect(status().isUnauthorized());
        post(authorToken(0), repliesPath("not-a-uuid"), Map.of("body", "Bad id.")).andExpect(status().isBadRequest());
        assertThat(messages.count()).isZero();
    }

    @Test
    void followUpsAreValidated() throws Exception {
        String a = authorToken(0);
        String id = ticket(a, "Question");

        post(a, repliesPath(id), Map.of("body", "   ")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[0].field").value("body"));
        post(a, repliesPath(id), Map.of()).andExpect(status().isBadRequest());
        post(a, repliesPath(id), Map.of("body", "x".repeat(ValidationLimits.MESSAGE_MAX + 1))).andExpect(status().isBadRequest());
        post(a, repliesPath(id), Map.of("body", "x".repeat(ValidationLimits.MESSAGE_MAX))).andExpect(status().isCreated());
        // the sender is always the signed-in author, whatever the request says
        post(a, repliesPath(id), Map.of("body", "Hello", "senderId", UUID.randomUUID().toString(), "visibility", "INTERNAL"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.messages[1].senderRole").value("AUTHOR"));
        assertThat(messages.findAll()).allSatisfy(m -> assertThat(m.getVisibility().name()).isEqualTo("PUBLIC"));
    }

    @Test
    void internalNotesStayHiddenEvenWhenTheAuthorKeepsWriting() throws Exception {
        String a = authorToken(0);
        String id = ticket(a, "Question");
        String admin = adminToken();
        String secret = "INTERNAL-" + UUID.randomUUID();
        post(admin, "/api/v1/admin/tickets/" + id + "/notes", Map.of("body", secret)).andExpect(status().isCreated());

        String result = body(post(a, repliesPath(id), Map.of("body", "A follow-up")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.messages.length()").value(1)));
        assertThat(result).doesNotContain(secret);
        assertThat(body(get(a, "/api/v1/author/tickets/" + id))).doesNotContain(secret);
    }

    // ---- awaiting reply ----

    @Test
    void theQueueKnowsWhichTicketsAreWaitingForTheTeam() throws Exception {
        String a = authorToken(0);
        String admin = adminToken();
        String fresh = ticket(a, "Fresh");
        String answered = ticket(a, "Answered");
        String resolved = ticket(a, "Resolved");
        post(admin, "/api/v1/admin/tickets/" + answered + "/replies", Map.of("body", "Done for now.")).andExpect(status().isCreated());
        patch(admin, "/api/v1/admin/tickets/" + resolved, Map.of("status", "RESOLVED")).andExpect(status().isOk());

        // never replied to: awaiting; replied to: not; resolved: never
        assertThat(awaitingIds(admin)).containsExactly(fresh);
        get(admin, "/api/v1/admin/tickets/" + fresh).andExpect(jsonPath("$.summary.awaitingReply").value(true));
        get(admin, "/api/v1/admin/tickets/" + answered).andExpect(jsonPath("$.summary.awaitingReply").value(false));
        get(admin, "/api/v1/admin/tickets/" + resolved).andExpect(jsonPath("$.summary.awaitingReply").value(false));

        // the author writes again: awaiting again; the team replies: cleared
        Thread.sleep(10);
        post(a, repliesPath(answered), Map.of("body", "One more question.")).andExpect(status().isCreated());
        assertThat(awaitingIds(admin)).containsExactlyInAnyOrder(fresh, answered);
        Thread.sleep(10);
        post(admin, "/api/v1/admin/tickets/" + answered + "/replies", Map.of("body", "Answered.")).andExpect(status().isCreated());
        assertThat(awaitingIds(admin)).containsExactly(fresh);
        get(admin, "/api/v1/admin/tickets?size=50").andExpect(status().isOk());
    }

    @Test
    void theAwaitingFilterCombinesWithTheOtherFiltersAndAnInternalNoteDoesNotCountAsAReply() throws Exception {
        String a = authorToken(0);
        String admin = adminToken();
        String id = ticket(a, "Needs an answer");
        post(admin, "/api/v1/admin/tickets/" + id + "/notes", Map.of("body", "Just a private note.")).andExpect(status().isCreated());

        assertThat(awaitingIds(admin)).containsExactly(id);   // a note is not a reply to the author
        assertThat((List<?>) read(get(admin, "/api/v1/admin/tickets?awaiting=true&status=CLOSED"), "$.data")).isEmpty();
        assertThat((List<?>) read(get(admin, "/api/v1/admin/tickets?awaiting=false"), "$.data")).hasSize(1);   // false means "no filter"
    }

    // ---- AI ----

    @Test
    void followUpsNeverTriggerTriageButTheDraftSeesTheConversation() throws Exception {
        llm.respondWith("{\"category\":\"Royalty & Payments\",\"priority\":\"High\",\"reason\":\"x\"}");
        String a = authorToken(0);
        String id = createTicket(a, null, "Royalty not received", "I have not received my royalty for 6 months.");
        awaitTriage(id);
        String admin = adminToken();
        post(admin, "/api/v1/admin/tickets/" + id + "/replies", Map.of("body", "We are checking your account.")).andExpect(status().isCreated());
        post(a, repliesPath(id), Map.of("body", "It is now 7 months. Any update?")).andExpect(status().isCreated());
        assertThat(llm.requests()).hasSize(1);   // triage ran once, at creation only

        llm.respondWith("Hi, thanks for your patience. BookLeaf Support Team");
        post(admin, "/api/v1/admin/tickets/" + id + "/ai-draft", null).andExpect(status().isOk());

        assertThat(llm.requests()).hasSize(2);
        String prompt = llm.requests().get(1).user();
        assertThat(prompt).contains("- Support: We are checking your account.")
                .contains("- Author: It is now 7 months. Any update?")
                .contains("The author has written again");
    }
}
