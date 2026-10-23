package com.bookleaf.support.ai;

import com.bookleaf.support.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Chat-completions client for an OpenAI-compatible endpoint (base URL, model and provider name all
 * come from configuration). The API key is read from the LLM_API_KEY environment variable and only
 * ever sent to the provider from this server. Provider error bodies are discarded, never forwarded.
 */
@Component
public class OpenAiLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiLlmClient.class);

    private final RestClient http;
    private final ObjectMapper objectMapper;
    private final AppProperties.Ai config;

    public OpenAiLlmClient(ObjectMapper objectMapper, AppProperties properties) {
        this.objectMapper = objectMapper;
        this.config = properties.ai();
        HttpClient jdkClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(config.connectTimeoutSeconds()))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(jdkClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(config.readTimeoutSeconds()));
        this.http = RestClient.builder().baseUrl(config.baseUrl()).requestFactory(requestFactory).build();
    }

    @Override
    public boolean isConfigured() {
        return config.hasApiKey();
    }

    @Override
    public String provider() {
        return config.providerName();
    }

    @Override
    public String model() {
        return config.model();
    }

    @Override
    public LlmResult complete(LlmRequest request) {
        if (!isConfigured()) {
            throw new AiUnavailableException("not_configured");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", config.model());
        body.put("temperature", request.temperature());
        body.put("max_tokens", request.maxTokens());
        body.put("messages", List.of(
                Map.of("role", "system", "content", request.system()),
                Map.of("role", "user", "content", request.user())));
        if (request.jsonMode()) {
            body.put("response_format", Map.of("type", "json_object"));
        }

        long started = System.nanoTime();
        String raw;
        try {
            raw = http.post()
                    .uri("/chat/completions")
                    .header("Authorization", "Bearer " + config.apiKey().trim())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException ex) {
            int status = ex.getStatusCode().value();
            String reason = status == 429 ? "rate_limited"
                    : (status == 401 || status == 403) ? "auth_failed"
                    : "provider_error_" + status;
            log.warn("LLM call failed: {}", reason);
            throw new AiUnavailableException(reason);
        } catch (ResourceAccessException ex) {
            log.warn("LLM call timed out or could not connect");
            throw new AiUnavailableException("timeout_or_network", ex);
        } catch (RuntimeException ex) {
            log.warn("LLM call failed unexpectedly: {}", ex.getClass().getSimpleName());
            throw new AiUnavailableException("provider_error", ex);
        }
        long latencyMs = (System.nanoTime() - started) / 1_000_000;

        try {
            JsonNode root = objectMapper.readTree(raw);
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            if (content.isMissingNode() || content.isNull() || content.asString().isBlank()) {
                throw new AiUnavailableException("empty_response");
            }
            JsonNode usage = root.path("usage");
            Integer input = usage.has("prompt_tokens") ? usage.path("prompt_tokens").intValue() : null;
            Integer output = usage.has("completion_tokens") ? usage.path("completion_tokens").intValue() : null;
            return new LlmResult(content.asString(), input, output, latencyMs);
        } catch (AiUnavailableException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new AiUnavailableException("unreadable_response", ex);
        }
    }
}
