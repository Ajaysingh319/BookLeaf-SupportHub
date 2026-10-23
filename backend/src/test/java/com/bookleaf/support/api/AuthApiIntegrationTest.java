package com.bookleaf.support.api;

import com.bookleaf.support.domain.UserAccount;
import com.bookleaf.support.repository.UserAccountRepository;
import com.bookleaf.support.testsupport.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Login, token checks and role separation (PRD FR-01, FR-02; TDD sections 5 and 13). */
class AuthApiIntegrationTest extends IntegrationTestBase {

    @Autowired private UserAccountRepository users;

    @Test
    void loginReturnsATokenAndASafeProfile() throws Exception {
        String result = body(post(null, "/api/v1/auth/login", Map.of("email", authorEmail(0), "password", authorPassword))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.user.role").value("AUTHOR"))
                .andExpect(jsonPath("$.user.email").value(authorEmail(0)))
                .andExpect(jsonPath("$.user.passwordHash").doesNotExist()));
        assertThat(result).doesNotContain("passwordHash");
    }

    @Test
    void emailIsMatchedCaseInsensitivelyAndTrimmed() throws Exception {
        post(null, "/api/v1/auth/login", Map.of("email", "  " + authorEmail(0).toUpperCase() + " ", "password", authorPassword))
                .andExpect(status().isOk());
    }

    @Test
    void wrongPasswordAndUnknownEmailGiveTheSameClearError() throws Exception {
        post(null, "/api/v1/auth/login", Map.of("email", authorEmail(0), "password", "not-the-password"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.error.message").value("Invalid email or password."))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
        post(null, "/api/v1/auth/login", Map.of("email", "nobody@example.test", "password", "whatever"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.message").value("Invalid email or password."));
    }

    @Test
    void blankCredentialsGetFieldLevelValidationErrors() throws Exception {
        post(null, "/api/v1/auth/login", Map.of("email", "", "password", ""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details.length()").value(2));
    }

    @Test
    void passwordsAreStoredOnlyAsBcryptHashes() {
        UserAccount author = users.findByEmail(authorEmail(0)).orElseThrow();
        assertThat(author.getPasswordHash()).startsWith("$2").isNotEqualTo(authorPassword);
        UserAccount admin = users.findByEmail(adminEmail).orElseThrow();
        assertThat(admin.getPasswordHash()).startsWith("$2").isNotEqualTo(adminPassword);
    }

    @Test
    void protectedRoutesRejectMissingAndInvalidTokens() throws Exception {
        get(null, "/api/v1/author/books")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
        get("not-a-real-token", "/api/v1/author/books").andExpect(status().isUnauthorized());
        get("not-a-real-token", "/api/v1/auth/me").andExpect(status().isUnauthorized());
        get(null, "/api/v1/admin/tickets").andExpect(status().isUnauthorized());
    }

    @Test
    void meReturnsTheCallerAndEveryResponseCarriesARequestId() throws Exception {
        get(authorToken(0), "/api/v1/auth/me")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("AUTHOR"))
                .andExpect(header().exists("X-Request-Id"));
    }

    @Test
    void authorsCannotUseAdminEndpointsAndAdminsCannotUseAuthorEndpoints() throws Exception {
        String author = authorToken(0);
        String admin = adminToken();
        get(author, "/api/v1/admin/tickets").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        get(author, "/api/v1/admin/tickets/summary").andExpect(status().isForbidden());
        post(author, "/api/v1/admin/tickets/" + java.util.UUID.randomUUID() + "/ai-draft", null).andExpect(status().isForbidden());
        patch(author, "/api/v1/admin/tickets/" + java.util.UUID.randomUUID(), Map.of("status", "CLOSED"))
                .andExpect(status().isForbidden());
        get(admin, "/api/v1/author/books").andExpect(status().isForbidden());
        get(admin, "/api/v1/author/tickets").andExpect(status().isForbidden());
    }

    @Test
    void healthAndConfigArePublicAndExposeNoSecrets() throws Exception {
        String health = body(get(null, "/api/v1/health").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP")));
        assertThat(health).doesNotContain("h2").doesNotContain("jdbc");

        String config = body(get(null, "/api/v1/config").andExpect(status().isOk())
                .andExpect(jsonPath("$.brandName").value("Test Portal"))
                .andExpect(jsonPath("$.options.categories.length()").value(6))
                .andExpect(jsonPath("$.options.priorities.length()").value(4))
                .andExpect(jsonPath("$.options.statuses.length()").value(4)));
        assertThat(config).doesNotContainIgnoringCase("secret").doesNotContainIgnoringCase("password")
                .doesNotContain(authorPassword).doesNotContainIgnoringCase("apikey");
    }

    @Test
    void unknownRoutesAndMethodsReturnTheCommonErrorShape() throws Exception {
        String token = authorToken(0);
        get(token, "/api/v1/does-not-exist").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.requestId").isNotEmpty());
        patch(token, "/api/v1/author/tickets/" + java.util.UUID.randomUUID(), Map.of("status", "CLOSED"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.error.code").value("METHOD_NOT_ALLOWED"));
    }
}
