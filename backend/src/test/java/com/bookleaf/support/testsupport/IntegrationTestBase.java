package com.bookleaf.support.testsupport;

import com.bookleaf.support.domain.AiOutcome;
import com.bookleaf.support.domain.Ticket;
import com.bookleaf.support.repository.AiEventRepository;
import com.bookleaf.support.repository.AttachmentRepository;
import com.bookleaf.support.repository.TicketMessageRepository;
import com.bookleaf.support.repository.TicketRepository;
import com.bookleaf.support.seed.SampleDataImporter;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Base for API integration tests (TDD section 13). They run the real application (controllers,
 * filters, services, JPA) against an in-memory database, seeded from the same sample dataset the
 * product uses, with a fake AI provider and a movable clock. The database is cleaned of tickets
 * after every test.
 *
 * Sessions are cookies, as in the product. "Tokens" in these helpers are the session cookie's value:
 * login() remembers each session's CSRF token, and writes send it automatically, exactly as the
 * browser app does.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestAiConfig.class)
public abstract class IntegrationTestBase {

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");
    private static final AtomicInteger ADDRESS_COUNTER = new AtomicInteger();

    @Autowired protected MockMvc mvc;
    @Autowired protected ObjectMapper mapper;
    @Autowired protected ControllableLlmClient llm;
    @Autowired protected MutableClock clock;
    @Autowired protected TicketRepository tickets;
    @Autowired protected TicketMessageRepository messages;
    @Autowired protected AttachmentRepository attachments;
    @Autowired protected AiEventRepository aiEvents;
    @Autowired private SampleDataImporter importer;
    @Autowired private ResourceLoader resourceLoader;

    @Value("${app.seed.resource}") private String datasetLocation;
    @Value("${app.seed.author-password}") protected String authorPassword;
    @Value("${app.seed.admin-email}") protected String adminEmail;
    @Value("${app.seed.admin-password}") protected String adminPassword;
    @Value("${app.auth.cookie.name}") protected String cookieName;

    protected JsonNode dataset;
    /** Each test signs in from its own address, so sign-in throttling never leaks between tests. */
    protected String remoteAddress;
    private final Map<String, String> csrfBySession = new ConcurrentHashMap<>();

    @BeforeEach
    void seedDatasetAndResetState() throws Exception {
        dataset = mapper.readTree(resourceLoader.getResource(datasetLocation).getInputStream());
        importer.importDataset(dataset);
        llm.reset();
        clock.reset();
        int n = ADDRESS_COUNTER.incrementAndGet();
        remoteAddress = "10." + (n / 65_000) + "." + ((n / 250) % 250) + "." + (n % 250 + 1);
        csrfBySession.clear();
    }

    /** Wait for any background triage to finish before tickets are deleted, so nothing races the cleanup. */
    @AfterEach
    void cleanUpTickets() {
        Awaitility.await().atMost(Duration.ofSeconds(15)).until(() ->
                tickets.findAll().stream().noneMatch(t -> t.getAiOutcome() == AiOutcome.PENDING));
        aiEvents.deleteAll();
        attachments.deleteAll();
        messages.deleteAll();
        tickets.deleteAll();
    }

    // ---- dataset helpers ----

    protected JsonNode author(int index) {
        return dataset.get("authors").get(index);
    }

    protected String authorEmail(int index) {
        return author(index).get("email").asString();
    }

    // ---- HTTP helpers ----

    protected String json(Object value) {
        return mapper.writeValueAsString(value);
    }

    /** A request from this test's address, optionally carrying the session cookie in {@code token}. */
    protected MockHttpServletRequestBuilder request(HttpMethod method, String path) {
        return MockMvcRequestBuilders.request(method, path).with(req -> {
            req.setRemoteAddr(remoteAddress);
            return req;
        });
    }

    /**
     * Sends a request as the session in {@code token} (a cookie value; null for no session). Writes carry the
     * CSRF token that went with that session at sign-in, as the browser app does.
     */
    protected ResultActions send(HttpMethod method, String token, String path, Object body) throws Exception {
        MockHttpServletRequestBuilder request = request(method, path);
        if (token != null) {
            request.cookie(new Cookie(cookieName, token));
            String csrf = csrfBySession.get(token);
            if (csrf != null && !SAFE_METHODS.contains(method.name())) {
                request.header("X-CSRF-Token", csrf);
            }
        }
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(json(body));
        return mvc.perform(request);
    }

    protected ResultActions get(String token, String path) throws Exception {
        return send(HttpMethod.GET, token, path, null);
    }

    protected ResultActions post(String token, String path, Object body) throws Exception {
        return send(HttpMethod.POST, token, path, body);
    }

    protected ResultActions patch(String token, String path, Object body) throws Exception {
        return send(HttpMethod.PATCH, token, path, body);
    }

    protected String body(ResultActions result) throws Exception {
        return result.andReturn().getResponse().getContentAsString();
    }

    protected <T> T read(ResultActions result, String jsonPath) throws Exception {
        return JsonPath.read(body(result), jsonPath);
    }

    // ---- accounts ----

    /** Signs in and returns the session cookie's value. */
    protected String login(String email, String password) throws Exception {
        MockHttpServletResponse response = post(null, "/api/v1/auth/login", Map.of("email", email, "password", password))
                .andReturn().getResponse();
        Cookie cookie = response.getCookie(cookieName);
        if (response.getStatus() != 200 || cookie == null) {
            throw new AssertionError("Sign-in failed with HTTP " + response.getStatus() + ": " + response.getContentAsString());
        }
        String csrf = JsonPath.read(response.getContentAsString(), "$.csrfToken");
        csrfBySession.put(cookie.getValue(), csrf);
        return cookie.getValue();
    }

    /** The CSRF token that belongs to a session returned by login(). */
    protected String csrfOf(String sessionToken) {
        return csrfBySession.get(sessionToken);
    }

    protected String authorToken(int authorIndex) throws Exception {
        return login(authorEmail(authorIndex), authorPassword);
    }

    protected String adminToken() throws Exception {
        return login(adminEmail, adminPassword);
    }

    protected String adminId(String adminToken) throws Exception {
        return read(get(adminToken, "/api/v1/auth/me"), "$.id");
    }

    // ---- tickets ----

    /** The API id of one of the caller's books, found by the dataset's own book id (BK001...). */
    protected String bookId(String authorToken, String sourceBookId) throws Exception {
        java.util.List<String> ids = read(get(authorToken, "/api/v1/author/books"),
                "$[?(@.sourceBookId=='" + sourceBookId + "')].id");
        return ids.get(0);
    }

    protected String createTicket(String authorToken, String bookId, String subject, String description) throws Exception {
        Map<String, Object> request = new java.util.HashMap<>();
        request.put("subject", subject);
        request.put("description", description);
        if (bookId != null) request.put("bookId", bookId);
        return read(post(authorToken, "/api/v1/author/tickets", request), "$.id");
    }

    /** Waits for the background triage of one ticket and returns it. */
    protected Ticket awaitTriage(String ticketId) {
        UUID id = UUID.fromString(ticketId);
        Awaitility.await().atMost(Duration.ofSeconds(15)).until(() ->
                tickets.findById(id).map(t -> t.getAiOutcome() != AiOutcome.PENDING).orElse(false));
        return tickets.findById(id).orElseThrow();
    }
}
