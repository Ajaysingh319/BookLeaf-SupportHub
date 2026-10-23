package com.bookleaf.support.web.error;

import org.springframework.http.HttpStatus;

/** Too many failed sign-ins: answered with 429 and a Retry-After header. */
public class TooManyAttemptsException extends ApiException {

    private final long retryAfterSeconds;

    public TooManyAttemptsException(long retryAfterSeconds) {
        super(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_ATTEMPTS",
                "Too many failed sign-in attempts. Please try again in " + minutes(retryAfterSeconds) + ".");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    private static String minutes(long seconds) {
        long minutes = Math.max(1, (seconds + 59) / 60);
        return minutes + (minutes == 1 ? " minute" : " minutes");
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
