package com.bookleaf.support.api;

import com.bookleaf.support.testsupport.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The production-style cookie setting: HTTPS only and Strict. This needs its own application context (and its
 * own in-memory database) because the cookie attributes come from configuration.
 */
@TestPropertySource(properties = {
        "app.auth.cookie.secure=true",
        "app.auth.cookie.same-site=Strict",
        "spring.datasource.url=jdbc:h2:mem:secure-cookie-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"
})
class SecureCookieIntegrationTest extends IntegrationTestBase {

    @Test
    void theCookieIsSecureAndStrictWhenConfiguredForProduction() throws Exception {
        MockHttpServletResponse response = post(null, "/api/v1/auth/login",
                Map.of("email", authorEmail(0), "password", authorPassword)).andReturn().getResponse();

        String setCookie = response.getHeader("Set-Cookie");
        assertThat(setCookie).contains("Secure").contains("HttpOnly").contains("SameSite=Strict").contains("Path=/api");
    }

    @Test
    void signingOutClearsTheCookieWithTheSameAttributes() throws Exception {
        String session = authorToken(0);
        MockHttpServletResponse response = post(session, "/api/v1/auth/logout", null).andReturn().getResponse();

        // a cookie can only be deleted by one that matches its Secure / SameSite / Path attributes
        assertThat(response.getHeader("Set-Cookie")).contains("Max-Age=0").contains("Secure").contains("SameSite=Strict")
                .contains("Path=/api");
    }
}
