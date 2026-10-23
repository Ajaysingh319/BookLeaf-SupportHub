package com.bookleaf.support.ai;

import com.bookleaf.support.config.AppProperties;
import com.bookleaf.support.domain.Priority;
import com.bookleaf.support.domain.TicketCategory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * Deterministic triage used when the AI is unavailable or returns unusable output. The rules
 * (PRD section 7) are read from a properties file, so they can be changed without touching code:
 * wrong/duplicate ISBN, missing/overdue royalty and defective printing are High; explicit severe or
 * repeated-failure language is Critical; general metadata updates are Low; the rest is Medium.
 * It is a safety net, not a substitute for the AI.
 */
@Component
public class FallbackTriage {

    private record CategoryRule(TicketCategory category, Pattern pattern) {
    }

    private record PriorityRule(Priority base, Pattern highRequires, Pattern high) {
    }

    private final String reason;
    private final List<CategoryRule> categoryRules = new ArrayList<>();
    private final TicketCategory defaultCategory;
    private final Pattern severe;
    private final Map<TicketCategory, PriorityRule> priorityRules = new EnumMap<>(TicketCategory.class);
    private final Priority defaultPriority;

    @Autowired
    public FallbackTriage(ResourceLoader resourceLoader, AppProperties properties) {
        this(load(resourceLoader.getResource(properties.ai().fallbackRulesLocation())));
    }

    /** Builds the rules from already-loaded properties (used by tests). */
    public FallbackTriage(Properties rules) {
        this.reason = required(rules, "fallback.reason");
        for (int i = 1; rules.getProperty("rule." + i + ".category") != null; i++) {
            categoryRules.add(new CategoryRule(
                    TicketCategory.valueOf(required(rules, "rule." + i + ".category")),
                    regex(required(rules, "rule." + i + ".patterns"))));
        }
        this.defaultCategory = TicketCategory.valueOf(required(rules, "default.category"));
        this.severe = regex(required(rules, "severe.patterns"));
        this.defaultPriority = Priority.valueOf(required(rules, "default.priority"));
        for (TicketCategory category : TicketCategory.values()) {
            String prefix = "priority." + category.name() + ".";
            if (rules.getProperty(prefix + "base") == null) {
                continue;
            }
            String requires = rules.getProperty(prefix + "high.requires");
            priorityRules.put(category, new PriorityRule(
                    Priority.valueOf(required(rules, prefix + "base")),
                    requires == null ? null : regex(requires),
                    regex(required(rules, prefix + "high.patterns"))));
        }
    }

    private static Properties load(Resource resource) {
        Properties properties = new Properties();
        try (InputStream in = resource.getInputStream()) {
            properties.load(in);
        } catch (IOException ex) {
            throw new UncheckedIOException("Cannot read fallback triage rules " + resource.getDescription(), ex);
        }
        return properties;
    }

    private static String required(Properties rules, String key) {
        String value = rules.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Fallback triage rules are missing: " + key);
        }
        return value;
    }

    private static Pattern regex(String value) {
        return Pattern.compile(value, Pattern.CASE_INSENSITIVE);
    }

    public TriageResult classify(String subject, String description) {
        String text = ((subject == null ? "" : subject) + " " + (description == null ? "" : description))
                .toLowerCase(Locale.ROOT);
        TicketCategory category = category(text);
        return new TriageResult(category, priority(category, text), reason);
    }

    private TicketCategory category(String text) {
        for (CategoryRule rule : categoryRules) {
            if (rule.pattern().matcher(text).find()) {
                return rule.category();
            }
        }
        return defaultCategory;
    }

    private Priority priority(TicketCategory category, String text) {
        if (severe.matcher(text).find()) {
            return Priority.CRITICAL;
        }
        PriorityRule rule = priorityRules.get(category);
        if (rule == null) {
            return defaultPriority;
        }
        boolean requirementMet = rule.highRequires() == null || rule.highRequires().matcher(text).find();
        return requirementMet && rule.high().matcher(text).find() ? Priority.HIGH : rule.base();
    }

    /** The reason text attached to every fallback result. */
    public String reason() {
        return reason;
    }
}
