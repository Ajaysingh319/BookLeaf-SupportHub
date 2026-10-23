package com.bookleaf.support.api;

import com.bookleaf.support.TestProperties;
import com.bookleaf.support.domain.Role;
import com.bookleaf.support.security.JwtService;
import com.bookleaf.support.security.SessionClaims;
import com.bookleaf.support.testsupport.IntegrationTestBase;
import com.bookleaf.support.testsupport.MutableClock;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Cookie sessions (TDD section 5): the cookie's flags, CSRF protection, signing out, sliding sessions with an
 * absolute limit, forged tokens and sign-in throttling. Test configuration: idle 60 minutes, absolute 720.
 */
class SessionSecurityIntegrationTest extends IntegrationTestBase {

    private static final String ALLOWED_ORIGIN = "http://frontend.test";

    @org.springframework.beans.factory.annotation.Autowired private JwtService real;

    private MockHttpServletResponse loginResponse(String email, String password, String address) throws Exception {
        MockHttpServletRequestBuilder request = request(HttpMethod.POST, "/api/v1/auth/login")
                .with(req -> {
                    req.setRemoteAddr(address);
                    return req;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("email", email, "password", password)));
        return mvc.perform(request).andReturn().getResponse();
    }

    /** A raw request with exactly the cookie, CSRF header and Origin given (null leaves each one out). */
    private ResultActions raw(HttpMethod method, String path, String session, String csrf, String origin, Object body) throws Exception {
        MockHttpServletRequestBuilder request = request(method, path);
        if (session != null) request.cookie(new Cookie(cookieName, session));
        if (csrf != null) request.header("X-CSRF-Token", csrf);
        if (origin != null) request.header("Origin", origin);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(json(body));
        return mvc.perform(request);
    }

    private static final Map<String, Object> A_TICKET = Map.of("subject", "Cookie session ticket", "description", "A description long enough.");

    // ---- the cookie ----

    @Test
    void theSessionCookieIsHttpOnlyScopedToTheApiAndLastsTheIdleTime() throws Exception {
        MockHttpServletResponse response = loginResponse(authorEmail(0), authorPassword, remoteAddress);

        assertThat(response.getStatus()).isEqualTo(200);
        String setCookie = response.getHeaders("Set-Cookie").stream().filter(h -> h.startsWith(cookieName + "=")).findFirst().orElseThrow();
        assertThat(setCookie).contains("HttpOnly").contains("SameSite=Lax").contains("Path=/api").containsPattern("Max-Age=(3600|3599)")
                .doesNotContain("Secure");   // test configuration is plain HTTP; production sets COOKIE_SECURE=true
        assertThat(setCookie).doesNotContain("Domain=");   // host-only: never shared with sibling subdomains
    }

    @Test
    void theTokenIsInTheCookieAndNeverInTheResponseBody() throws Exception {
        MockHttpServletResponse response = loginResponse(authorEmail(0), authorPassword, remoteAddress);
        String token = response.getCookie(cookieName).getValue();

        assertThat(token).startsWith("eyJ");
        assertThat(response.getContentAsString()).doesNotContain(token).doesNotContain("eyJ");
    }

    @Test
    void aFailedSignInSetsNoCookie() throws Exception {
        MockHttpServletResponse response = loginResponse(authorEmail(0), "wrong-password", remoteAddress);
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeaders("Set-Cookie")).isEmpty();
    }

    // ---- the session is only the cookie ----

    @Test
    void aBearerHeaderIsNotASessionEvenWithAValidToken() throws Exception {
        String session = authorToken(0);
        mvc.perform(request(HttpMethod.GET, "/api/v1/author/books").header("Authorization", "Bearer " + session))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
        get(session, "/api/v1/author/books").andExpect(status().isOk());
    }

    @Test
    void tamperedExpiredForgedAndMissingCookiesAreAllJustUnauthenticated() throws Exception {
        String session = authorToken(0);
        String[] parts = session.split("\\.");

        get(null, "/api/v1/author/books").andExpect(status().isUnauthorized());
        get("not.a.token", "/api/v1/author/books").andExpect(status().isUnauthorized());
        get(parts[0] + "." + parts[1] + "x." + parts[2], "/api/v1/author/books").andExpect(status().isUnauthorized());
        get(parts[0] + "." + parts[1] + "." + parts[2].substring(0, parts[2].length() - 3) + "AAA", "/api/v1/author/books")
                .andExpect(status().isUnauthorized());

        clock.advance(Duration.ofMinutes(61));   // idle time is up
        get(session, "/api/v1/author/books").andExpect(status().isUnauthorized());
    }

    @Test
    void aTokenSignedWithAnotherKeyCannotMakeAnAuthorAnAdmin() throws Exception {
        JwtService attacker = new JwtService(TestProperties.withAuth("attacker-secret-that-is-at-least-32-chars!", 60), clock);
        SessionClaims forged = attacker.newSession(UUID.randomUUID(), Role.ADMIN);
        String token = attacker.sign(forged);

        get(token, "/api/v1/admin/tickets").andExpect(status().isUnauthorized());
        raw(HttpMethod.PATCH, "/api/v1/admin/tickets/" + UUID.randomUUID(), token, forged.csrfToken(), null, Map.of("status", "CLOSED"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aValidSignatureForAUserWhoNoLongerExistsDoesNotCrashTheSessionEndpoint() throws Exception {
        SessionClaims ghost = real.newSession(UUID.randomUUID(), Role.AUTHOR);   // a user id that is not in the database
        raw(HttpMethod.GET, "/api/v1/auth/session", real.sign(ghost), null, null, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user").doesNotExist())
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("Max-Age=0")));
    }

    // ---- the session endpoint ----

    @Test
    void theSessionEndpointTellsTheAppWhoIsSignedInAndGivesTheCsrfTokenBack() throws Exception {
        String session = authorToken(0);

        get(session, "/api/v1/auth/session").andExpect(status().isOk())
                .andExpect(jsonPath("$.user.email").value(authorEmail(0)))
                .andExpect(jsonPath("$.user.role").value("AUTHOR"))
                .andExpect(jsonPath("$.csrfToken").value(csrfOf(session)))
                .andExpect(jsonPath("$.user.passwordHash").doesNotExist());
    }

    @Test
    void withoutASessionTheSessionEndpointAnswersOkWithNothing() throws Exception {
        get(null, "/api/v1/auth/session").andExpect(status().isOk())
                .andExpect(jsonPath("$.user").doesNotExist()).andExpect(jsonPath("$.csrfToken").doesNotExist());
        get("garbage", "/api/v1/auth/session").andExpect(status().isOk()).andExpect(jsonPath("$.user").doesNotExist());
    }

    // ---- CSRF ----

    @Test
    void writesNeedTheSessionsCsrfTokenButReadsDoNot() throws Exception {
        String session = authorToken(0);
        String csrf = csrfOf(session);

        raw(HttpMethod.GET, "/api/v1/author/books", session, null, null, null).andExpect(status().isOk());
        raw(HttpMethod.POST, "/api/v1/author/tickets", session, null, null, A_TICKET)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("CSRF_INVALID"));
        raw(HttpMethod.POST, "/api/v1/author/tickets", session, "", null, A_TICKET).andExpect(status().isForbidden());
        raw(HttpMethod.POST, "/api/v1/author/tickets", session, "wrong-token", null, A_TICKET).andExpect(status().isForbidden());
        assertThat(tickets.count()).isZero();

        raw(HttpMethod.POST, "/api/v1/author/tickets", session, csrf, null, A_TICKET).andExpect(status().isCreated());
        assertThat(tickets.count()).isEqualTo(1);
    }

    @Test
    void aCsrfTokenFromAnotherSessionDoesNotWork() throws Exception {
        String mine = authorToken(0);
        String someoneElses = authorToken(1);
        assertThat(csrfOf(mine)).isNotEqualTo(csrfOf(someoneElses));

        raw(HttpMethod.POST, "/api/v1/author/tickets", mine, csrfOf(someoneElses), null, A_TICKET).andExpect(status().isForbidden());
        // even the same person's other session has its own token
        String again = authorToken(0);
        raw(HttpMethod.POST, "/api/v1/author/tickets", mine, csrfOf(again), null, A_TICKET).andExpect(status().isForbidden());
        assertThat(tickets.count()).isZero();
    }

    @Test
    void everyKindOfWriteIsProtected() throws Exception {
        String admin = adminToken();
        String ticketId = createTicket(authorToken(0), null, "Protected", "A description long enough.");
        awaitTriage(ticketId);
        String base = "/api/v1/admin/tickets/" + ticketId;

        raw(HttpMethod.PATCH, base, admin, null, null, Map.of("status", "CLOSED")).andExpect(status().isForbidden());
        raw(HttpMethod.POST, base + "/notes", admin, null, null, Map.of("body", "x")).andExpect(status().isForbidden());
        raw(HttpMethod.POST, base + "/replies", admin, null, null, Map.of("body", "x")).andExpect(status().isForbidden());
        raw(HttpMethod.POST, base + "/ai-draft", admin, null, null, null).andExpect(status().isForbidden());
        raw(HttpMethod.PUT, base, admin, null, null, Map.of("status", "CLOSED")).andExpect(status().isForbidden());
        raw(HttpMethod.DELETE, base, admin, null, null, null).andExpect(status().isForbidden());
        get(admin, base).andExpect(jsonPath("$.summary.status").value("OPEN"));
    }

    @Test
    void aRequestThatNamesAnotherSiteAsItsOriginIsRefusedEvenWithTheRightToken() throws Exception {
        String session = authorToken(0);
        String csrf = csrfOf(session);

        raw(HttpMethod.POST, "/api/v1/author/tickets", session, csrf, "https://evil.example", A_TICKET)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("CSRF_INVALID"));
        raw(HttpMethod.POST, "/api/v1/author/tickets", session, csrf, "http://frontend.test.evil.example", A_TICKET)
                .andExpect(status().isForbidden());
        raw(HttpMethod.POST, "/api/v1/author/tickets", session, csrf, "null", A_TICKET).andExpect(status().isForbidden());
        assertThat(tickets.count()).isZero();

        raw(HttpMethod.POST, "/api/v1/author/tickets", session, csrf, ALLOWED_ORIGIN, A_TICKET).andExpect(status().isCreated());
    }

    @Test
    void theBrowserPreflightIsAnsweredOnlyForTheConfiguredOriginAndAllowsTheCsrfHeaderWithCookies() throws Exception {
        mvc.perform(request(HttpMethod.OPTIONS, "/api/v1/author/tickets")
                        .header("Origin", ALLOWED_ORIGIN)
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "content-type,x-csrf-token"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", ALLOWED_ORIGIN))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
        mvc.perform(request(HttpMethod.OPTIONS, "/api/v1/author/tickets")
                        .header("Origin", "https://evil.example")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden());
    }

    // ---- signing out ----

    @Test
    void signingOutDeletesTheCookieAndRevokesTheSessionOnTheServer() throws Exception {
        String session = authorToken(0);
        String csrf = csrfOf(session);
        String otherSession = authorToken(0);   // the same person signed in somewhere else

        MockHttpServletResponse response = raw(HttpMethod.POST, "/api/v1/auth/logout", session, csrf, null, null)
                .andExpect(status().isNoContent()).andReturn().getResponse();
        assertThat(response.getHeader("Set-Cookie")).contains(cookieName + "=").contains("Max-Age=0").contains("HttpOnly");

        // a copy of the old cookie (for example one that was stolen) no longer works
        get(session, "/api/v1/author/books").andExpect(status().isUnauthorized());
        get(session, "/api/v1/auth/session").andExpect(jsonPath("$.user").doesNotExist());
        // the other session is unaffected
        get(otherSession, "/api/v1/author/books").andExpect(status().isOk());
    }

    @Test
    void signingOutWorksEvenWhenThereIsNoValidSession() throws Exception {
        raw(HttpMethod.POST, "/api/v1/auth/logout", null, null, null, null).andExpect(status().isNoContent())
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("Max-Age=0")));
        String session = authorToken(0);
        clock.advance(Duration.ofMinutes(90));
        raw(HttpMethod.POST, "/api/v1/auth/logout", session, null, null, null).andExpect(status().isNoContent());
    }

    // ---- short sessions that renew while in use ----

    @Test
    void aSessionIsRenewedOnlyWhenHalfItsIdleTimeIsGoneAndKeepsItsCsrfToken() throws Exception {
        String session = authorToken(0);

        // fresh: nothing to renew
        assertThat(get(session, "/api/v1/author/books").andReturn().getResponse().getHeaders("Set-Cookie")).isEmpty();
        clock.advance(Duration.ofMinutes(20));
        assertThat(get(session, "/api/v1/author/books").andReturn().getResponse().getHeaders("Set-Cookie")).isEmpty();

        // 35 minutes in, 25 left: a fresh cookie arrives with the response
        clock.advance(Duration.ofMinutes(15));
        MockHttpServletResponse renewal = get(session, "/api/v1/author/books").andExpect(status().isOk()).andReturn().getResponse();
        String renewed = renewal.getCookie(cookieName).getValue();
        assertThat(renewed).isNotEqualTo(session);
        assertThat(renewal.getHeader("Set-Cookie")).containsPattern("Max-Age=(3600|3599)");

        // the renewed cookie is the same session: same CSRF token, and it outlives the original
        get(renewed, "/api/v1/auth/session").andExpect(jsonPath("$.csrfToken").value(csrfOf(session)));
        clock.advance(Duration.ofMinutes(30));    // 65 minutes after sign-in: the original has expired
        get(session, "/api/v1/author/books").andExpect(status().isUnauthorized());
        get(renewed, "/api/v1/author/books").andExpect(status().isOk());
    }

    @Test
    void anIdleSessionExpiresAfterTheIdleTime() throws Exception {
        String session = authorToken(0);
        clock.advance(Duration.ofMinutes(59));
        get(session, "/api/v1/author/books").andExpect(status().isOk());
        clock.advance(Duration.ofMinutes(2));   // 61 minutes without renewal... but the request above renewed it
        get(session, "/api/v1/author/books").andExpect(status().isUnauthorized());   // the original cookie is what we kept sending
    }

    @Test
    void noMatterHowActiveSomeoneIsTheSessionEndsAtTheAbsoluteLimit() throws Exception {
        String session = authorToken(0);
        String csrf = csrfOf(session);
        String cookie = session;

        // use the app every 30 minutes for 11.5 hours, always sending the newest cookie
        for (int i = 0; i < 23; i++) {
            clock.advance(Duration.ofMinutes(30));
            MockHttpServletResponse response = get(cookie, "/api/v1/author/books").andExpect(status().isOk()).andReturn().getResponse();
            Cookie fresh = response.getCookie(cookieName);
            if (fresh != null) cookie = fresh.getValue();
        }
        raw(HttpMethod.GET, "/api/v1/auth/session", cookie, null, null, null).andExpect(jsonPath("$.csrfToken").value(csrf));

        clock.advance(Duration.ofMinutes(45));   // 12 hours 15 minutes after signing in
        get(cookie, "/api/v1/author/books").andExpect(status().isUnauthorized());
    }

    // ---- sign-in throttling through the real API ----

    @Test
    void fiveWrongPasswordsLockTheAccountFromThatAddressEvenForTheRightPassword() throws Exception {
        for (int i = 0; i < 5; i++) {
            assertThat(loginResponse(authorEmail(0), "wrong-" + i, remoteAddress).getStatus()).isEqualTo(401);
        }
        MockHttpServletResponse locked = loginResponse(authorEmail(0), authorPassword, remoteAddress);

        assertThat(locked.getStatus()).isEqualTo(429);
        assertThat(locked.getHeaders("Set-Cookie")).isEmpty();
        assertThat(Long.parseLong(locked.getHeader("Retry-After"))).isBetween(1L, 15L * 60);
        assertThat(locked.getContentAsString()).contains("TOO_MANY_ATTEMPTS").contains("Too many failed sign-in attempts")
                .contains("requestId");
    }

    @Test
    void theLockEndsAfterFifteenMinutes() throws Exception {
        for (int i = 0; i < 5; i++) loginResponse(authorEmail(0), "wrong", remoteAddress);
        assertThat(loginResponse(authorEmail(0), authorPassword, remoteAddress).getStatus()).isEqualTo(429);

        clock.advance(Duration.ofMinutes(16));
        assertThat(loginResponse(authorEmail(0), authorPassword, remoteAddress).getStatus()).isEqualTo(200);
    }

    @Test
    void otherAccountsAreNotLockedAndAUnknownEmailIsThrottledTheSameWay() throws Exception {
        for (int i = 0; i < 5; i++) loginResponse(authorEmail(0), "wrong", remoteAddress);
        assertThat(loginResponse(authorEmail(1), authorPassword, remoteAddress).getStatus()).isEqualTo(200);

        for (int i = 0; i < 5; i++) {
            assertThat(loginResponse("nobody@example.test", "wrong", remoteAddress).getStatus()).isEqualTo(401);
        }
        MockHttpServletResponse unknown = loginResponse("nobody@example.test", "wrong", remoteAddress);
        assertThat(unknown.getStatus()).isEqualTo(429);   // exactly like a real account: nothing to learn from it
        assertThat(unknown.getContentAsString()).contains("TOO_MANY_ATTEMPTS");
    }

    @Test
    void aCorrectSignInResetsTheCountForThatAccount() throws Exception {
        for (int round = 0; round < 3; round++) {
            for (int i = 0; i < 4; i++) loginResponse(authorEmail(0), "wrong", remoteAddress);
            assertThat(loginResponse(authorEmail(0), authorPassword, remoteAddress).getStatus()).isEqualTo(200);
        }
    }

    @Test
    void anAttackerTheLockoutAppliesPerAddressSoItCannotLockOutTheRealUserFromAnotherAddress() throws Exception {
        for (int i = 0; i < 5; i++) loginResponse(authorEmail(0), "wrong", "198.51.100.66");
        assertThat(loginResponse(authorEmail(0), authorPassword, "198.51.100.66").getStatus()).isEqualTo(429);

        // the real person, on their own network, is not affected by someone else's guessing
        assertThat(loginResponse(authorEmail(0), authorPassword, "203.0.113.9").getStatus()).isEqualTo(200);
    }

    @Test
    void healthConfigSessionAndLoginStayPublicWhileEverythingElseNeedsASession() throws Exception {
        for (String path : List.of("/api/v1/health", "/api/v1/config", "/api/v1/auth/session")) {
            get(null, path).andExpect(status().isOk());
        }
        for (String path : List.of("/api/v1/auth/me", "/api/v1/author/books", "/api/v1/admin/tickets", "/api/v1/admin/admins")) {
            get(null, path).andExpect(status().isUnauthorized());
        }
    }

    @Test
    void errorResponsesFromTheFilterStillCarryARequestId() throws Exception {
        get(null, "/api/v1/author/books").andExpect(jsonPath("$.requestId").isNotEmpty()).andExpect(header().exists("X-Request-Id"));
        raw(HttpMethod.POST, "/api/v1/author/tickets", authorToken(0), null, null, A_TICKET)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.requestId").isNotEmpty());
    }
}
