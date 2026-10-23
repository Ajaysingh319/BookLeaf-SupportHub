package com.bookleaf.support.ai;

/**
 * The AI could not give a usable answer (not configured, rate-limited, timed out, provider error,
 * or malformed output). Carries a short reason code for logs and the AI event record; raw provider
 * messages are never kept, so they cannot reach clients.
 */
public class AiUnavailableException extends RuntimeException {

    private final String reasonCode;

    public AiUnavailableException(String reasonCode) {
        super("AI unavailable: " + reasonCode);
        this.reasonCode = reasonCode;
    }

    public AiUnavailableException(String reasonCode, Throwable cause) {
        super("AI unavailable: " + reasonCode, cause);
        this.reasonCode = reasonCode;
    }

    public String reasonCode() {
        return reasonCode;
    }
}
