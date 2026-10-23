package com.bookleaf.support.ai;

import com.bookleaf.support.domain.Priority;
import com.bookleaf.support.domain.TicketCategory;

/** A category and priority recommendation for a human admin (from the AI or the rule-based fallback). */
public record TriageResult(TicketCategory category, Priority priority, String reason) {
}
