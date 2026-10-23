package com.bookleaf.support.api;

import com.bookleaf.support.domain.Role;
import com.bookleaf.support.domain.UserAccount;
import com.bookleaf.support.dto.ValidationLimits;
import com.bookleaf.support.repository.UserAccountRepository;
import com.bookleaf.support.testsupport.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Queue search, assignee filter, unassign and reassign (PRD screen inventory "search/filters"; PDF "assign tickets"). */
class AdminQueueSearchIntegrationTest extends IntegrationTestBase {

    @Autowired private UserAccountRepository users;
    @Autowired private PasswordEncoder encoder;

    private String ticket(String authorToken, String bookId, String subject) throws Exception {
        String id = createTicket(authorToken, bookId, subject, "A description that is long enough to be valid.");
        awaitTriage(id);
        Thread.sleep(5);
        return id;
    }

    private List<String> ids(String admin, String query) throws Exception {
        return read(get(admin, "/api/v1/admin/tickets?size=50" + query).andExpect(status().isOk()), "$.data[*].id");
    }

    /** MockMvc encodes the URL itself, so the search text is passed as typed. */
    private static String q(String text) {
        return "&q=" + text;
    }

    /** A second admin, created once and reused by later tests (accounts are not cleaned between tests). */
    private UserAccount secondAdmin() {
        return users.findByEmail("second.admin@test.example").orElseGet(() -> {
            UserAccount admin = new UserAccount();
            admin.setName("Alpha Second Admin");
            admin.setEmail("second.admin@test.example");
            admin.setRole(Role.ADMIN);
            admin.setPasswordHash(encoder.encode("another-admin-password-1"));
            return users.save(admin);
        });
    }

    // ---- search ----

    @Test
    void searchMatchesTheSubjectAuthorNameEmailAndBookTitleCaseInsensitively() throws Exception {
        String a = authorToken(0);
        String b = authorToken(1);
        String bookA = bookId(a, author(0).get("books").get(0).get("book_id").asString());
        String bySubject = ticket(a, null, "Missing Payout question");
        String byBook = ticket(a, bookA, "Something else entirely");
        String other = ticket(b, null, "Unrelated topic");
        String admin = adminToken();

        assertThat(ids(admin, q("payout"))).containsExactly(bySubject);
        assertThat(ids(admin, q("PAYOUT"))).containsExactly(bySubject);
        assertThat(ids(admin, q(author(0).get("name").asString().split(" ")[0]))).containsExactlyInAnyOrder(bySubject, byBook);
        assertThat(ids(admin, q(authorEmail(1)))).containsExactly(other);
        assertThat(ids(admin, q(authorEmail(0).substring(0, 8)))).containsExactlyInAnyOrder(bySubject, byBook);
        assertThat(ids(admin, q(author(0).get("books").get(0).get("title").asString().substring(0, 6)))).containsExactly(byBook);
        assertThat(ids(admin, q("no ticket mentions this phrase"))).isEmpty();
    }

    @Test
    void aBlankSearchIsIgnoredAndTheTextIsTrimmed() throws Exception {
        String a = authorToken(0);
        String one = ticket(a, null, "First");
        String two = ticket(a, null, "Second");
        String admin = adminToken();

        assertThat(ids(admin, "&q=")).containsExactlyInAnyOrder(one, two);
        assertThat(ids(admin, q("   "))).containsExactlyInAnyOrder(one, two);
        assertThat(ids(admin, q("  first  "))).containsExactly(one);
    }

    @Test
    void percentAndUnderscoreInTheSearchMatchThemselvesNotEverything() throws Exception {
        String a = authorToken(0);
        String percent = ticket(a, null, "I am 100% sure");
        String underscore = ticket(a, null, "ticket_one");
        ticket(a, null, "ticket two");
        String admin = adminToken();

        assertThat(ids(admin, q("%"))).containsExactly(percent);
        assertThat(ids(admin, q("_"))).containsExactly(underscore);
        assertThat(ids(admin, q("ticket_"))).containsExactly(underscore);   // a wildcard "_" would also match "ticket "
    }

    @Test
    void searchCombinesWithTheOtherFiltersAndRejectsTooLongText() throws Exception {
        String a = authorToken(0);
        String open = ticket(a, null, "Payout open");
        String closed = ticket(a, null, "Payout closed");
        String admin = adminToken();
        patch(admin, "/api/v1/admin/tickets/" + closed, Map.of("status", "CLOSED")).andExpect(status().isOk());

        assertThat(ids(admin, q("payout") + "&status=OPEN")).containsExactly(open);
        assertThat(ids(admin, q("payout") + "&status=CLOSED")).containsExactly(closed);
        get(admin, "/api/v1/admin/tickets?q=" + "x".repeat(ValidationLimits.SEARCH_MAX + 1)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[0].field").value("q"));
        get(admin, "/api/v1/admin/tickets?q=" + "x".repeat(ValidationLimits.SEARCH_MAX)).andExpect(status().isOk());
        get(authorToken(0), "/api/v1/admin/tickets?q=anything").andExpect(status().isForbidden());
    }

    @Test
    void searchDoesNotChangeTheQueueOrderOrThePaginationTotals() throws Exception {
        String a = authorToken(0);
        String admin = adminToken();
        String first = ticket(a, null, "Payout one");
        String second = ticket(a, null, "Payout two");
        String third = ticket(a, null, "Payout three");
        patch(admin, "/api/v1/admin/tickets/" + third, Map.of("priority", "CRITICAL")).andExpect(status().isOk());

        assertThat(ids(admin, q("payout"))).containsExactly(third, first, second);
        get(admin, "/api/v1/admin/tickets?size=2&q=payout").andExpect(jsonPath("$.pagination.totalItems").value(3))
                .andExpect(jsonPath("$.pagination.totalPages").value(2));
    }

    // ---- assignee filter ----

    @Test
    void theQueueCanBeFilteredByAssigneeOrByUnassigned() throws Exception {
        String a = authorToken(0);
        String admin = adminToken();
        UserAccount other = secondAdmin();
        String mine = ticket(a, null, "Mine");
        String theirs = ticket(a, null, "Theirs");
        String nobody = ticket(a, null, "Nobody");
        Map<String, Object> assignMe = new HashMap<>();
        assignMe.put("assignedAdminId", adminId(admin));
        Map<String, Object> assignOther = new HashMap<>();
        assignOther.put("assignedAdminId", other.getId().toString());
        patch(admin, "/api/v1/admin/tickets/" + mine, assignMe).andExpect(status().isOk());
        patch(admin, "/api/v1/admin/tickets/" + theirs, assignOther).andExpect(status().isOk());

        assertThat(ids(admin, "&assignedTo=" + adminId(admin))).containsExactly(mine);
        assertThat(ids(admin, "&assignedTo=" + other.getId())).containsExactly(theirs);
        assertThat(ids(admin, "&assignedTo=unassigned")).containsExactly(nobody);
        assertThat(ids(admin, "&assignedTo=UNASSIGNED")).containsExactly(nobody);
        assertThat(ids(admin, "")).hasSize(3);
        get(admin, "/api/v1/admin/tickets?assignedTo=not-an-id").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[0].field").value("assignedTo"));
    }

    // ---- reassign and unassign ----

    @Test
    void anAdminCanReassignATicketToAnotherAdminAndRemoveTheAssignee() throws Exception {
        String id = ticket(authorToken(0), null, "Assign me around");
        String admin = adminToken();
        UserAccount other = secondAdmin();
        String path = "/api/v1/admin/tickets/" + id;

        Map<String, Object> toMe = new HashMap<>();
        toMe.put("assignedAdminId", adminId(admin));
        patch(admin, path, toMe).andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.assignedAdminName").value("Test Admin"));

        Map<String, Object> toOther = new HashMap<>();
        toOther.put("assignedAdminId", other.getId().toString());
        patch(admin, path, toOther).andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.assignedAdminName").value("Alpha Second Admin"));

        patch(admin, path, Map.of("unassign", true)).andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.assignedAdminId").doesNotExist())
                .andExpect(jsonPath("$.summary.assignedAdminName").doesNotExist());
        patch(admin, path, Map.of("unassign", true)).andExpect(status().isOk());   // unassigning an unassigned ticket is harmless
    }

    @Test
    void unassignCannotBeCombinedWithAnAssigneeAndFalseAloneChangesNothing() throws Exception {
        String id = ticket(authorToken(0), null, "Conflict");
        String admin = adminToken();
        String path = "/api/v1/admin/tickets/" + id;
        Map<String, Object> both = new HashMap<>();
        both.put("assignedAdminId", adminId(admin));
        both.put("unassign", true);

        patch(admin, path, both).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[0].field").value("unassign"));
        patch(admin, path, Map.of("unassign", false)).andExpect(status().isBadRequest());   // nothing to change
        // the failed attempts changed nothing
        get(admin, path).andExpect(jsonPath("$.summary.assignedAdminId").doesNotExist());
    }

    @Test
    void theAdminListIsForAdminsOnlyAndSortedByName() throws Exception {
        secondAdmin();
        String admin = adminToken();

        get(admin, "/api/v1/admin/admins").andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].name").value("Alpha Second Admin"))
                .andExpect(jsonPath("$[1].name").value("Test Admin"))
                .andExpect(jsonPath("$[0].passwordHash").doesNotExist());
        get(authorToken(0), "/api/v1/admin/admins").andExpect(status().isForbidden());
        get(null, "/api/v1/admin/admins").andExpect(status().isUnauthorized());
        // authors are never offered as assignees
        assertThat(body(get(admin, "/api/v1/admin/admins"))).doesNotContain(authorEmail(0));
    }
}
