package com.bookleaf.support.domain;

import java.util.Optional;

/** Ticket priority. Declared most urgent first, so ordinal() is a sort key (0 = Critical). */
public enum Priority {
    CRITICAL("Critical"),
    HIGH("High"),
    MEDIUM("Medium"),
    LOW("Low");

    private final String label;

    Priority(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static Optional<Priority> fromLabel(String text) {
        if (text == null) {
            return Optional.empty();
        }
        String trimmed = text.trim();
        for (Priority priority : values()) {
            if (priority.label.equalsIgnoreCase(trimmed)) {
                return Optional.of(priority);
            }
        }
        return Optional.empty();
    }
}
