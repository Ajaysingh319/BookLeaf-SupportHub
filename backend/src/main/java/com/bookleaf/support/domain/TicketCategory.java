package com.bookleaf.support.domain;

import java.util.Optional;

/** The six categories from the assignment. The label is the exact text given to and expected from the AI. */
public enum TicketCategory {
    ROYALTY_PAYMENTS("Royalty & Payments"),
    ISBN_METADATA("ISBN & Metadata Issues"),
    PRINTING_QUALITY("Printing & Quality"),
    DISTRIBUTION_AVAILABILITY("Distribution & Availability"),
    BOOK_STATUS_PRODUCTION("Book Status & Production Updates"),
    GENERAL_INQUIRY("General Inquiry");

    private final String label;

    TicketCategory(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** Exact (case-insensitive) match against a label; empty when the text is not one of the six. */
    public static Optional<TicketCategory> fromLabel(String text) {
        if (text == null) {
            return Optional.empty();
        }
        String trimmed = text.trim();
        for (TicketCategory category : values()) {
            if (category.label.equalsIgnoreCase(trimmed)) {
                return Optional.of(category);
            }
        }
        return Optional.empty();
    }
}
