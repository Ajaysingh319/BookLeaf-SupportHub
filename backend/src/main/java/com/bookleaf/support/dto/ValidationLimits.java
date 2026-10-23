package com.bookleaf.support.dto;

/**
 * Input limits from the TDD (6.1) and PRD (FR-04). Annotations need compile-time constants, so
 * they live in this one place: the validators, the database column lengths and the limits
 * sent to the client (GET /api/v1/config) all read from here, so there is a single source.
 */
public final class ValidationLimits {

    public static final int SUBJECT_MIN = 3;
    public static final int SUBJECT_MAX = 160;
    public static final int DESCRIPTION_MIN = 10;
    public static final int DESCRIPTION_MAX = 10_000;
    /** Replies and internal notes. */
    public static final int MESSAGE_MAX = 10_000;
    /** RFC 5321 maximum email address length. */
    public static final int EMAIL_MAX = 254;
    public static final int PASSWORD_MAX = 128;

    private ValidationLimits() {
    }
}
