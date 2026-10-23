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
    /** Longest text accepted by the admin queue search box. */
    public static final int SEARCH_MAX = 100;
    /** RFC 5321 maximum email address length. */
    public static final int EMAIL_MAX = 254;
    public static final int PASSWORD_MAX = 128;

    /** Cleaned attachment file names are cut to this length (the column size). */
    public static final int FILE_NAME_MAX = 100;
    /** Largest file the attachments column can hold (32 MiB); app.attachments.max-file-bytes may not exceed it. */
    public static final int ATTACHMENT_COLUMN_MAX_BYTES = 33_554_432;

    private ValidationLimits() {
    }
}
