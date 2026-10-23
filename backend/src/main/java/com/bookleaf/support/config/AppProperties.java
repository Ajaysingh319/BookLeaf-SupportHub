package com.bookleaf.support.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;

/**
 * Every tunable and environment-specific value, bound from application.properties and the
 * environment (see .env.example). Nothing here has a default in code: a missing or invalid value
 * stops startup with a message naming the property, instead of silently using a guess.
 */
@Validated
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        @Valid @NotNull Auth auth,
        @Valid @NotNull Cors cors,
        @Valid @NotNull Pagination pagination,
        @Valid @NotNull Ai ai,
        @Valid @NotNull Seed seed,
        @Valid @NotNull Ui ui,
        @Valid @NotNull Attachments attachments) {

    /**
     * Session settings. The session lives in an HttpOnly cookie: tokenTtlMinutes is how long it stays valid
     * without activity (it is renewed while the person keeps using the app), sessionMaxMinutes is the
     * absolute limit from sign-in, however active they are.
     */
    public record Auth(
            @NotBlank String secret,
            @Positive long tokenTtlMinutes,
            @Positive long sessionMaxMinutes,
            @Valid @NotNull Cookie cookie,
            @Valid @NotNull Throttle throttle) {

        /** secure must be true on HTTPS deployments; sameSite is Lax, Strict, or None (None needs secure). */
        public record Cookie(@NotBlank String name, boolean secure,
                             @NotBlank @Pattern(regexp = "Lax|Strict|None", message = "must be Lax, Strict or None") String sameSite) {
        }

        /** Failed sign-ins are counted per (address, email) and per address; reaching a limit locks it for lockoutMinutes. */
        public record Throttle(@Positive int maxFailedLogins, @Positive int maxFailedLoginsPerIp,
                               @Positive long lockoutMinutes, @Positive int maxTrackedKeys) {
        }
    }

    public record Cors(@NotBlank String allowedOrigin) {
    }

    public record Pagination(@Positive int defaultSize, @Positive int maxSize) {
    }

    /** apiKey is optional on purpose: without it the app runs with rule-based triage and manual replies. */
    public record Ai(
            String apiKey,
            @NotBlank String providerName,
            @NotBlank String model,
            @NotBlank String baseUrl,
            /** Optional. Sent as reasoning_effort only when set (needed for "thinking" models). */
            String reasoningEffort,
            @NotBlank String promptsLocation,
            @NotBlank String fallbackRulesLocation,
            @Positive int connectTimeoutSeconds,
            @Positive int readTimeoutSeconds,
            /** Total tries per call for quick, transient failures (429 and 5xx). 1 means no retry. */
            @Positive int maxAttempts,
            @PositiveOrZero long retryDelayMs,
            @Valid @NotNull Triage triage,
            @Valid @NotNull Draft draft) {

        public boolean hasApiKey() {
            return apiKey != null && !apiKey.isBlank();
        }

        public boolean hasReasoningEffort() {
            return reasoningEffort != null && !reasoningEffort.isBlank();
        }
    }

    public record Triage(double temperature, @Positive int maxTokens, @Positive int descriptionLimit,
                         @Positive int reasonMaxLength) {
    }

    public record Draft(double temperature, @Positive int maxTokens, @Positive int descriptionLimit,
                        @Positive int replyLimit, @Positive int maxLength, @Positive int recentReplies) {
    }

    /** Demo account passwords are only needed when seeding, so they are checked there, not at startup. */
    public record Seed(boolean enabled, @NotBlank String resource, String authorPassword, String adminEmail,
                       String adminName, String adminPassword) {
    }

    /**
     * Files an author may attach to a ticket. allowedTypes are matched against what the file's bytes really are
     * (image/jpeg, image/png, image/webp, application/pdf are the types the detector knows).
     */
    public record Attachments(
            @Positive @Max(com.bookleaf.support.dto.ValidationLimits.ATTACHMENT_COLUMN_MAX_BYTES) int maxFileBytes,
            @Positive int maxFilesPerTicket,
            @NotEmpty List<@NotBlank String> allowedTypes) {
    }

    /** Values the browser client is told at startup (served by GET /api/v1/config). */
    public record Ui(
            @NotBlank String brandName,
            @NotBlank @Pattern(regexp = "[A-Z]{3}", message = "must be a 3-letter ISO 4217 currency code") String currencyCode,
            @NotBlank String locale,
            @Positive int ticketPollMs,
            @Positive int listPollMs,
            @Positive int royaltyPayoutDays,
            @Positive int minimumPayout,
            @NotBlank String inProductionStatusPrefix,
            @Positive int dashboardRecentTickets,
            @Positive int staleTicketHours) {
    }
}
