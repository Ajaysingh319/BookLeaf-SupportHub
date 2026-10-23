package com.bookleaf.support.ai;

import com.bookleaf.support.config.AppProperties;
import com.bookleaf.support.domain.Priority;
import com.bookleaf.support.domain.TicketCategory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.HashSet;
import java.util.Set;

/**
 * Strictly validates the model's triage JSON (TDD 7.4): exactly the three expected fields, a
 * category and priority that are exactly one of the allowed values, and a short reason.
 * Anything else is treated as a failed AI call so the rule-based fallback takes over.
 */
@Component
public class TriageParser {

    private static final Set<String> EXPECTED_FIELDS = Set.of("category", "priority", "reason");

    private final ObjectMapper objectMapper;
    private final int maxReasonLength;

    public TriageParser(ObjectMapper objectMapper, AppProperties properties) {
        this.objectMapper = objectMapper;
        this.maxReasonLength = properties.ai().triage().reasonMaxLength();
    }

    public TriageResult parse(String content) {
        JsonNode root;
        try {
            root = objectMapper.readTree(stripCodeFence(content));
        } catch (RuntimeException ex) {
            throw new AiUnavailableException("invalid_output", ex);
        }
        if (root == null || !root.isObject()) {
            throw new AiUnavailableException("invalid_output");
        }

        Set<String> fields = new HashSet<>();
        root.propertyNames().forEach(fields::add);
        if (!fields.equals(EXPECTED_FIELDS)) {
            throw new AiUnavailableException("invalid_output");
        }

        TicketCategory category = TicketCategory.fromLabel(textOf(root, "category"))
                .orElseThrow(() -> new AiUnavailableException("invalid_output"));
        Priority priority = Priority.fromLabel(textOf(root, "priority"))
                .orElseThrow(() -> new AiUnavailableException("invalid_output"));

        String reason = textOf(root, "reason");
        reason = reason == null || reason.isBlank() ? "No reason given." : reason.strip();
        if (reason.length() > maxReasonLength) {
            reason = reason.substring(0, maxReasonLength);
        }
        return new TriageResult(category, priority, reason);
    }

    private static String textOf(JsonNode root, String field) {
        JsonNode value = root.get(field);
        return value != null && value.isString() ? value.asString() : null;
    }

    /** Some models wrap JSON in a markdown code fence even when asked not to. */
    private static String stripCodeFence(String content) {
        String text = content == null ? "" : content.strip();
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            int lastFence = text.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                return text.substring(firstNewline + 1, lastFence).strip();
            }
        }
        return text;
    }
}
