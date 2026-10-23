package com.bookleaf.support.api;

import com.bookleaf.support.testsupport.IntegrationTestBase;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Attachments: what is accepted, what is refused, who may see a file, and how it is served. Limits: 1024 bytes, 3 per ticket. */
class AttachmentsIntegrationTest extends IntegrationTestBase {

    private static final byte[] PNG = bytes(0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4);
    private static final byte[] JPEG = bytes(0xFF, 0xD8, 0xFF, 0xE0, 0, 16, 'J', 'F', 'I', 'F');
    private static final byte[] WEBP = bytes('R', 'I', 'F', 'F', 1, 0, 0, 0, 'W', 'E', 'B', 'P', 'V', 'P', '8', ' ');
    private static final byte[] PDF = "%PDF-1.7\n%%EOF".getBytes(StandardCharsets.US_ASCII);

    private static byte[] bytes(int... values) {
        byte[] result = new byte[values.length];
        for (int i = 0; i < values.length; i++) result[i] = (byte) values[i];
        return result;
    }

    private ResultActions upload(String session, String ticketId, String fileName, byte[] content) throws Exception {
        return uploadRaw(session, csrfOf(session), ticketId, new MockMultipartFile("file", fileName, "application/octet-stream", content));
    }

    private ResultActions uploadRaw(String session, String csrf, String ticketId, MockMultipartFile file) throws Exception {
        MockMultipartHttpServletRequestBuilder request = MockMvcRequestBuilders.multipart("/api/v1/author/tickets/" + ticketId + "/attachments")
                .file(file).with(req -> {
                    req.setRemoteAddr(remoteAddress);
                    return req;
                });
        if (session != null) request.cookie(new Cookie(cookieName, session));
        if (csrf != null) request.header("X-CSRF-Token", csrf);
        return mvc.perform(request);
    }

    private String newTicket(String session) throws Exception {
        return createTicket(session, null, "Ticket with files", "A description that is long enough.");
    }

    // ---- accepted files ----

    @Test
    void anAuthorCanAttachEachAllowedKindOfFileAndSeesThemOnTheTicket() throws Exception {
        String session = authorToken(0);
        String ticketId = newTicket(session);

        upload(session, ticketId, "screenshot.png", PNG).andExpect(status().isCreated())
                .andExpect(jsonPath("$.fileName").value("screenshot.png")).andExpect(jsonPath("$.contentType").value("image/png"))
                .andExpect(jsonPath("$.sizeBytes").value(PNG.length)).andExpect(jsonPath("$.id").isNotEmpty());
        upload(session, ticketId, "scan.jpeg", JPEG).andExpect(status().isCreated()).andExpect(jsonPath("$.fileName").value("scan.jpg"));
        upload(session, ticketId, "royalty statement.pdf", PDF).andExpect(status().isCreated())
                .andExpect(jsonPath("$.contentType").value("application/pdf"));

        get(session, "/api/v1/author/tickets/" + ticketId).andExpect(status().isOk())
                .andExpect(jsonPath("$.attachments.length()").value(3))
                .andExpect(jsonPath("$.attachments[*].fileName", org.hamcrest.Matchers.containsInAnyOrder(
                        "screenshot.png", "scan.jpg", "royalty statement.pdf")))
                .andExpect(jsonPath("$.attachments[*].contentType", org.hamcrest.Matchers.containsInAnyOrder(
                        "image/png", "image/jpeg", "application/pdf")));
        get(adminToken(), "/api/v1/admin/tickets/" + ticketId).andExpect(jsonPath("$.attachments.length()").value(3));
    }

    @Test
    void webpIsRecognisedToo() throws Exception {
        String session = authorToken(0);
        upload(session, newTicket(session), "pic.webp", WEBP).andExpect(status().isCreated())
                .andExpect(jsonPath("$.contentType").value("image/webp"));
    }

    @Test
    void aTicketWithoutFilesHasAnEmptyList() throws Exception {
        String session = authorToken(0);
        get(session, "/api/v1/author/tickets/" + newTicket(session)).andExpect(jsonPath("$.attachments.length()").value(0));
    }

    // ---- refused files ----

    @Test
    void theFilesContentDecidesItsTypeNotItsNameOrDeclaredType() throws Exception {
        String session = authorToken(0);
        String ticketId = newTicket(session);
        byte[] script = "<script>alert(1)</script>".getBytes(StandardCharsets.UTF_8);

        // a script renamed to .png, declared as image/png
        uploadRaw(session, csrfOf(session), ticketId, new MockMultipartFile("file", "photo.png", "image/png", script))
                .andExpect(status().isUnsupportedMediaType()).andExpect(jsonPath("$.error.code").value("UNSUPPORTED_FILE_TYPE"));
        // an HTML page, an executable header and an empty-looking PDF header are all refused
        upload(session, ticketId, "page.html", "<html></html>".getBytes(StandardCharsets.UTF_8)).andExpect(status().isUnsupportedMediaType());
        upload(session, ticketId, "tool.exe", bytes('M', 'Z', 0x90, 0)).andExpect(status().isUnsupportedMediaType());
        upload(session, ticketId, "a.pdf", "%PD".getBytes(StandardCharsets.US_ASCII)).andExpect(status().isUnsupportedMediaType());
        // a PNG named .exe is a PNG: it is accepted, stored as image/png and renamed to match
        upload(session, ticketId, "tool.exe", PNG).andExpect(status().isCreated())
                .andExpect(jsonPath("$.fileName").value("tool.png")).andExpect(jsonPath("$.contentType").value("image/png"));
        get(session, "/api/v1/author/tickets/" + ticketId).andExpect(jsonPath("$.attachments.length()").value(1));
    }

    @Test
    void anEmptyFileIsRefused() throws Exception {
        String session = authorToken(0);
        upload(session, newTicket(session), "empty.png", new byte[0]).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[0].field").value("file"));
    }

    @Test
    void aFileOverTheLimitIsRefusedWithAClearMessage() throws Exception {
        String session = authorToken(0);
        String ticketId = newTicket(session);
        byte[] big = Arrays.copyOf(PNG, 1025);
        upload(session, ticketId, "big.png", big).andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.error.code").value("FILE_TOO_LARGE")).andExpect(jsonPath("$.error.message").value("Files can be at most 1 KB."));
        // exactly the limit is fine
        upload(session, ticketId, "exact.png", Arrays.copyOf(PNG, 1024)).andExpect(status().isCreated());
    }

    @Test
    void aRequestFarOverTheLimitIsStoppedBeforeItIsRead() throws Exception {
        String session = authorToken(0);
        upload(session, newTicket(session), "huge.png", Arrays.copyOf(PNG, 10_000)).andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.error.code").value("FILE_TOO_LARGE"));
    }

    @Test
    void aTicketHoldsAtMostTheConfiguredNumberOfFiles() throws Exception {
        String session = authorToken(0);
        String ticketId = newTicket(session);
        for (int i = 0; i < 3; i++) upload(session, ticketId, "f" + i + ".png", PNG).andExpect(status().isCreated());

        upload(session, ticketId, "one-too-many.png", PNG).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ATTACHMENT_LIMIT")).andExpect(jsonPath("$.error.message").value("A ticket can have at most 3 attachments."));
        get(session, "/api/v1/author/tickets/" + ticketId).andExpect(jsonPath("$.attachments.length()").value(3));
    }

    @Test
    void theRequestMustContainAFilePart() throws Exception {
        String session = authorToken(0);
        String ticketId = newTicket(session);
        MockMultipartFile wrongName = new MockMultipartFile("document", "x.png", "image/png", PNG);
        uploadRaw(session, csrfOf(session), ticketId, wrongName).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[0].field").value("file"));
    }

    @Test
    void aClosedTicketTakesNoMoreFiles() throws Exception {
        String session = authorToken(0);
        String ticketId = newTicket(session);
        awaitTriage(ticketId);
        patch(adminToken(), "/api/v1/admin/tickets/" + ticketId, Map.of("status", "CLOSED")).andExpect(status().isOk());

        upload(session, ticketId, "late.png", PNG).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("TICKET_CLOSED"));
    }

    // ---- file names ----

    @Test
    void fileNamesAreCleanedOfPathsAndOddCharacters() throws Exception {
        String session = authorToken(0);
        String ticketId = newTicket(session);

        upload(session, ticketId, "..\\..\\windows\\system32\\evil.png", PNG).andExpect(jsonPath("$.fileName").value("evil.png"));
        upload(session, ticketId, "../../etc/passwd", PNG).andExpect(jsonPath("$.fileName").value("passwd.png"));
        upload(session, ticketId, "we\"ird\r\nname<>.png", PNG).andExpect(jsonPath("$.fileName").value("we_ird_name_.png"));
    }

    @Test
    void anUnusableNameBecomesAttachmentAndLongNamesAreCut() throws Exception {
        String session = authorToken(0);
        String ticketId = newTicket(session);

        upload(session, ticketId, "???.png", PNG).andExpect(jsonPath("$.fileName").value("attachment.png"));
        String name = upload(session, ticketId, "x".repeat(300) + ".png", PNG).andReturn().getResponse().getContentAsString();
        assertThat(com.jayway.jsonpath.JsonPath.<String>read(name, "$.fileName")).hasSize(100).endsWith(".png");
    }

    // ---- who can do what ----

    @Test
    void anotherAuthorCannotUploadToOrDownloadFromMyTicket() throws Exception {
        String mine = authorToken(0);
        String other = authorToken(1);
        String ticketId = newTicket(mine);
        String attachmentId = read(upload(mine, ticketId, "mine.png", PNG), "$.id");

        upload(other, ticketId, "sneaky.png", PNG).andExpect(status().isNotFound());
        get(other, "/api/v1/author/tickets/" + ticketId + "/attachments/" + attachmentId).andExpect(status().isNotFound());
        get(other, "/api/v1/author/tickets/" + ticketId).andExpect(status().isNotFound());
    }

    @Test
    void anAttachmentCannotBeFetchedThroughAnotherTicketOfTheSameAuthor() throws Exception {
        String session = authorToken(0);
        String first = newTicket(session);
        String second = newTicket(session);
        String attachmentId = read(upload(session, first, "a.png", PNG), "$.id");

        get(session, "/api/v1/author/tickets/" + second + "/attachments/" + attachmentId).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ATTACHMENT_NOT_FOUND"));
        get(adminToken(), "/api/v1/admin/tickets/" + second + "/attachments/" + attachmentId).andExpect(status().isNotFound());
    }

    @Test
    void filesNeedASessionAndTheCsrfToken() throws Exception {
        String session = authorToken(0);
        String ticketId = newTicket(session);
        MockMultipartFile file = new MockMultipartFile("file", "a.png", "image/png", PNG);

        uploadRaw(null, null, ticketId, file).andExpect(status().isUnauthorized());
        uploadRaw(session, null, ticketId, file).andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("CSRF_INVALID"));
        uploadRaw(session, "wrong", ticketId, file).andExpect(status().isForbidden());
        get(null, "/api/v1/author/tickets/" + ticketId + "/attachments/" + java.util.UUID.randomUUID()).andExpect(status().isUnauthorized());
    }

    @Test
    void anAdminCannotUseTheAuthorUploadRouteButCanDownloadAnyAttachment() throws Exception {
        String session = authorToken(0);
        String ticketId = newTicket(session);
        String attachmentId = read(upload(session, ticketId, "proof.pdf", PDF), "$.id");
        String admin = adminToken();

        upload(admin, ticketId, "admin.png", PNG).andExpect(status().isForbidden());
        MockHttpServletResponse response = get(admin, "/api/v1/admin/tickets/" + ticketId + "/attachments/" + attachmentId)
                .andExpect(status().isOk()).andReturn().getResponse();
        assertThat(response.getContentAsByteArray()).isEqualTo(PDF);
        // an author cannot use the admin route
        get(session, "/api/v1/admin/tickets/" + ticketId + "/attachments/" + attachmentId).andExpect(status().isForbidden());
    }

    // ---- how files are served ----

    @Test
    void downloadsAreAttachmentsWithTheDetectedTypeAndHardenedHeaders() throws Exception {
        String session = authorToken(0);
        String ticketId = newTicket(session);
        String attachmentId = read(upload(session, ticketId, "my statement.png", PNG), "$.id");

        MockHttpServletResponse response = get(session, "/api/v1/author/tickets/" + ticketId + "/attachments/" + attachmentId)
                .andExpect(status().isOk()).andReturn().getResponse();

        assertThat(response.getContentAsByteArray()).isEqualTo(PNG);
        assertThat(response.getContentType()).isEqualTo("image/png");
        assertThat(response.getHeader("Content-Disposition")).startsWith("attachment").contains("filename*=UTF-8''my%20statement.png");
        assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getHeader("Content-Security-Policy")).contains("sandbox");
        assertThat(response.getHeader("Cache-Control")).contains("no-store").contains("private");
        assertThat(response.getContentLength()).isEqualTo(PNG.length);
    }

    @Test
    void unknownAttachmentsAndTicketsAreNotFound() throws Exception {
        String session = authorToken(0);
        String ticketId = newTicket(session);
        get(session, "/api/v1/author/tickets/" + ticketId + "/attachments/" + java.util.UUID.randomUUID()).andExpect(status().isNotFound());
        get(session, "/api/v1/author/tickets/" + java.util.UUID.randomUUID() + "/attachments/" + java.util.UUID.randomUUID()).andExpect(status().isNotFound());
        get(session, "/api/v1/author/tickets/" + ticketId + "/attachments/not-a-uuid").andExpect(status().isBadRequest());
    }

    @Test
    void theClientConfigTellsTheAppTheLimits() throws Exception {
        get(null, "/api/v1/config").andExpect(jsonPath("$.attachments.maxFileBytes").value(1024))
                .andExpect(jsonPath("$.attachments.maxFilesPerTicket").value(3))
                .andExpect(jsonPath("$.attachments.allowedTypes.length()").value(4));
    }

    @Test
    void uploadingDoesNotReopenOrFlagATicketForReply() throws Exception {
        String session = authorToken(0);
        String ticketId = newTicket(session);
        awaitTriage(ticketId);
        String admin = adminToken();
        post(admin, "/api/v1/admin/tickets/" + ticketId + "/replies", Map.of("body", "Done.", "status", "RESOLVED")).andExpect(status().isCreated());

        upload(session, ticketId, "later.png", PNG).andExpect(status().isCreated());
        get(admin, "/api/v1/admin/tickets/" + ticketId).andExpect(jsonPath("$.summary.status").value("RESOLVED"));
    }
}
