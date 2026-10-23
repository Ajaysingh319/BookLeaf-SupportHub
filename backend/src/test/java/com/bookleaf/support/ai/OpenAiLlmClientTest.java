package com.bookleaf.support.ai;

import com.bookleaf.support.TestProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The real HTTP client against a local fake provider: what it sends, how it reads answers, and how
 * it maps every kind of failure to a short reason code without leaking provider details.
 */
class OpenAiLlmClientTest {

    private static final String GOOD_ANSWER = "{\"choices\":[{\"message\":{\"content\":\"{\\\"ok\\\":true}\"}}],"
            + "\"usage\":{\"prompt_tokens\":92,\"completion_tokens\":30}}";

    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
    private final AtomicInteger counter = new AtomicInteger();
    private HttpServer server;
    /** Decides each response: given the 1-based request number, returns {status, body}. */
    private volatile IntFunction<Object[]> responder = n -> new Object[]{200, GOOD_ANSWER};
    private volatile long delayMs = 0;

    private record RecordedRequest(String path, String authorization, JsonNode body) {
    }

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            byte[] in = exchange.getRequestBody().readAllBytes();
            requests.add(new RecordedRequest(exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    mapper.readTree(new String(in, StandardCharsets.UTF_8))));
            if (delayMs > 0) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            Object[] response = responder.apply(counter.incrementAndGet());
            byte[] out = ((String) response[1]).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders((Integer) response[0], out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private OpenAiLlmClient client(String apiKey, String effort, int attempts, int readTimeoutSeconds) {
        String baseUrl = "http://localhost:" + server.getAddress().getPort() + "/v1";
        return new OpenAiLlmClient(mapper, TestProperties.withLlm(apiKey, baseUrl, effort, attempts, 5, readTimeoutSeconds));
    }

    private static LlmClient.LlmRequest request(boolean jsonMode) {
        return new LlmClient.LlmRequest("system text", "user text", 0.0, 512, jsonMode);
    }

    private String errorReason(OpenAiLlmClient client) {
        try {
            client.complete(request(true));
        } catch (AiUnavailableException ex) {
            return ex.reasonCode();
        }
        throw new AssertionError("Expected the call to fail");
    }

    // ---- what is sent ----

    @Test
    void sendsTheModelPromptsLimitsAndTheKeyToTheChatCompletionsEndpoint() {
        LlmClient.LlmResult result = client("secret-key", "none", 1, 5).complete(request(true));

        assertThat(requests).hasSize(1);
        RecordedRequest sent = requests.get(0);
        assertThat(sent.path()).isEqualTo("/v1/chat/completions");
        assertThat(sent.authorization()).isEqualTo("Bearer secret-key");
        assertThat(sent.body().get("model").asString()).isEqualTo("test-model");
        assertThat(sent.body().get("temperature").asDouble()).isZero();
        assertThat(sent.body().get("max_tokens").asInt()).isEqualTo(512);
        assertThat(sent.body().get("response_format").get("type").asString()).isEqualTo("json_object");
        assertThat(sent.body().get("reasoning_effort").asString()).isEqualTo("none");
        assertThat(sent.body().get("messages").get(0).get("role").asString()).isEqualTo("system");
        assertThat(sent.body().get("messages").get(0).get("content").asString()).isEqualTo("system text");
        assertThat(sent.body().get("messages").get(1).get("content").asString()).isEqualTo("user text");

        assertThat(result.content()).isEqualTo("{\"ok\":true}");
        assertThat(result.inputTokens()).isEqualTo(92);
        assertThat(result.outputTokens()).isEqualTo(30);
        assertThat(result.latencyMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void optionalSettingsAreLeftOutWhenNotUsed() {
        client("k", null, 1, 5).complete(request(false));
        client("k", "   ", 1, 5).complete(request(false));

        for (RecordedRequest sent : requests) {
            assertThat(sent.body().has("reasoning_effort")).isFalse();   // models that do not support it are not sent it
            assertThat(sent.body().has("response_format")).isFalse();    // drafts are free text, not JSON
        }
    }

    @Test
    void withoutAnApiKeyNothingIsSentAtAll() {
        OpenAiLlmClient client = client("   ", null, 1, 5);
        assertThat(client.isConfigured()).isFalse();
        assertThatThrownBy(() -> client.complete(request(true)))
                .isInstanceOfSatisfying(AiUnavailableException.class, ex -> assertThat(ex.reasonCode()).isEqualTo("not_configured"));
        assertThat(requests).isEmpty();
    }

    // ---- retries ----

    @Test
    void aBriefRateLimitOrBusySignalIsRetriedOnceAndThenSucceeds() {
        responder = n -> n == 1 ? new Object[]{429, "{\"error\":\"slow down\"}"} : new Object[]{200, GOOD_ANSWER};
        assertThat(client("k", null, 2, 5).complete(request(true)).content()).isEqualTo("{\"ok\":true}");
        assertThat(requests).hasSize(2);

        requests.clear();
        counter.set(0);
        responder = n -> n == 1 ? new Object[]{503, "overloaded"} : new Object[]{200, GOOD_ANSWER};
        client("k", null, 2, 5).complete(request(true));
        assertThat(requests).hasSize(2);
    }

    @Test
    void repeatedTransientFailuresStopAtTheConfiguredAttemptsWithAShortReasonCode() {
        responder = n -> new Object[]{503, "secret provider detail: quota project-123"};
        assertThat(errorReason(client("k", null, 3, 5))).isEqualTo("provider_error_503");
        assertThat(requests).hasSize(3);

        requests.clear();
        counter.set(0);
        responder = n -> new Object[]{429, "rate limit detail"};
        assertThat(errorReason(client("k", null, 2, 5))).isEqualTo("rate_limited");
        assertThat(requests).hasSize(2);
    }

    @Test
    void withOneAttemptThereIsNoRetry() {
        responder = n -> new Object[]{503, "busy"};
        assertThat(errorReason(client("k", null, 1, 5))).isEqualTo("provider_error_503");
        assertThat(requests).hasSize(1);
    }

    @Test
    void errorsThatCannotImproveAreNotRetried() {
        responder = n -> new Object[]{401, "bad key"};
        assertThat(errorReason(client("k", null, 3, 5))).isEqualTo("auth_failed");
        assertThat(requests).hasSize(1);

        requests.clear();
        counter.set(0);
        responder = n -> new Object[]{403, "forbidden"};
        assertThat(errorReason(client("k", null, 3, 5))).isEqualTo("auth_failed");

        requests.clear();
        counter.set(0);
        responder = n -> new Object[]{400, "bad request"};
        assertThat(errorReason(client("k", null, 3, 5))).isEqualTo("provider_error_400");
        assertThat(requests).hasSize(1);

        requests.clear();
        counter.set(0);
        responder = n -> new Object[]{404, "no such model"};
        assertThat(errorReason(client("k", null, 3, 5))).isEqualTo("provider_error_404");
        assertThat(requests).hasSize(1);
    }

    @Test
    void providerMessagesNeverAppearInTheException() {
        responder = n -> new Object[]{500, "INTERNAL-PROVIDER-DETAIL-xyz"};
        assertThatThrownBy(() -> client("k", null, 1, 5).complete(request(true)))
                .isInstanceOf(AiUnavailableException.class)
                .hasMessageNotContaining("INTERNAL-PROVIDER-DETAIL");
    }

    // ---- slow and malformed answers ----

    @Test
    void aSlowProviderTimesOutAndIsNotRetried() {
        delayMs = 2500;
        assertThat(errorReason(client("k", null, 3, 1))).isEqualTo("timeout_or_network");
        // the single slow request is not repeated (the server may still be finishing the first one)
        assertThat(requests.size()).isLessThanOrEqualTo(1);
    }

    @Test
    void anUnreachableProviderIsReportedAsANetworkFailure() {
        int port = server.getAddress().getPort();
        server.stop(0);
        OpenAiLlmClient client = new OpenAiLlmClient(mapper,
                TestProperties.withLlm("k", "http://localhost:" + port + "/v1", null, 2, 5, 5));
        assertThat(errorReason(client)).isEqualTo("timeout_or_network");
    }

    @Test
    void emptyOrUnreadableAnswersAreFailures() {
        responder = n -> new Object[]{200, "{\"choices\":[{\"message\":{\"content\":\"   \"}}]}"};
        assertThat(errorReason(client("k", null, 1, 5))).isEqualTo("empty_response");

        responder = n -> new Object[]{200, "{\"choices\":[]}"};
        assertThat(errorReason(client("k", null, 1, 5))).isEqualTo("empty_response");

        responder = n -> new Object[]{200, "<html>not json</html>"};
        assertThat(errorReason(client("k", null, 1, 5))).isEqualTo("unreadable_response");
    }

    @Test
    void tokenCountsAreOptional() {
        responder = n -> new Object[]{200, "{\"choices\":[{\"message\":{\"content\":\"hello\"}}]}"};
        LlmClient.LlmResult result = client("k", null, 1, 5).complete(request(false));
        assertThat(result.content()).isEqualTo("hello");
        assertThat(result.inputTokens()).isNull();
        assertThat(result.outputTokens()).isNull();
    }

    @Test
    void theProviderAndModelNamesComeFromConfiguration() {
        OpenAiLlmClient client = client("k", null, 1, 5);
        assertThat(client.provider()).isEqualTo("test-provider");
        assertThat(client.model()).isEqualTo("test-model");
        assertThat(client.isConfigured()).isTrue();
    }
}
