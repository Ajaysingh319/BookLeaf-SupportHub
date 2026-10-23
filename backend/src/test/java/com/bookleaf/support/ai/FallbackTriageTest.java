package com.bookleaf.support.ai;

import com.bookleaf.support.TestProperties;
import com.bookleaf.support.domain.Priority;
import com.bookleaf.support.domain.TicketCategory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The brief's sample queries and PRD section 7 rules, run against the rules file shipped with the app. */
class FallbackTriageTest {

    private final FallbackTriage fallback = new FallbackTriage(new DefaultResourceLoader(), TestProperties.create());

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "I published my book 4 months ago and still haven't received any royalty. What's going on? | ROYALTY_PAYMENTS | HIGH",
            "I haven't received any royalty for 6 months | ROYALTY_PAYMENTS | HIGH",
            "My royalty amount seems too low. I sold 200 copies but only received 3000. | ROYALTY_PAYMENTS | MEDIUM",
            "My book is showing a different ISBN on Amazon than what is on the physical copy. | ISBN_METADATA | HIGH",
            "I received my author copies and the print quality is terrible. Images are blurry and pages are misaligned. | PRINTING_QUALITY | HIGH",
            "My book is published but it is showing as Currently Unavailable on Amazon. | DISTRIBUTION_AVAILABILITY | MEDIUM",
            "It has been 3 weeks and my book is still in typesetting. When will it be done? | BOOK_STATUS_PRODUCTION | MEDIUM",
            "Can I update the description of my book on Amazon after it is already live? | GENERAL_INQUIRY | LOW",
            "Can I update my author bio? | GENERAL_INQUIRY | LOW",
            "How do I get an ISBN under my own imprint? | ISBN_METADATA | MEDIUM",
            "Hello, I just wanted to say thank you for the great experience. | GENERAL_INQUIRY | MEDIUM",
    })
    void classifiesTheBriefsSampleQueries(String text, TicketCategory category, Priority priority) {
        TriageResult result = fallback.classify("Question", text);
        assertThat(result.category()).isEqualTo(category);
        assertThat(result.priority()).isEqualTo(priority);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "I have not received my royalty and this is urgent, I will take legal action.",
            "I have asked about my royalty several times and there is no response.",
    })
    void severeOrRepeatedFailureLanguageIsCritical(String text) {
        assertThat(fallback.classify("Royalty", text).priority()).isEqualTo(Priority.CRITICAL);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"|", "x|y"})
    void alwaysReturnsAUsableResult(String subject, String description) {
        TriageResult result = fallback.classify(subject, description);
        assertThat(result.category()).isNotNull();
        assertThat(result.priority()).isNotNull();
        assertThat(result.reason()).isEqualTo(fallback.reason());
    }

    @Test
    void rulesComeFromTheFileSoTheyCanBeChangedWithoutCode() {
        Properties custom = new Properties();
        custom.setProperty("fallback.reason", "custom reason");
        custom.setProperty("rule.1.category", "DISTRIBUTION_AVAILABILITY");
        custom.setProperty("rule.1.patterns", "banana");
        custom.setProperty("default.category", "GENERAL_INQUIRY");
        custom.setProperty("severe.patterns", "meteor");
        custom.setProperty("default.priority", "LOW");
        FallbackTriage custom1 = new FallbackTriage(custom);

        TriageResult banana = custom1.classify("A banana", "is here");
        assertThat(banana.category()).isEqualTo(TicketCategory.DISTRIBUTION_AVAILABILITY);
        assertThat(banana.priority()).isEqualTo(Priority.LOW);
        assertThat(banana.reason()).isEqualTo("custom reason");
        assertThat(custom1.classify("A meteor", "approaches").priority()).isEqualTo(Priority.CRITICAL);
    }

    @Test
    void aRuleCanSetItsOwnPriorityButSevereLanguageStillWins() {
        Properties custom = new Properties();
        custom.setProperty("fallback.reason", "r");
        custom.setProperty("rule.1.category", "GENERAL_INQUIRY");
        custom.setProperty("rule.1.priority", "LOW");
        custom.setProperty("rule.1.patterns", "banana");
        custom.setProperty("default.category", "GENERAL_INQUIRY");
        custom.setProperty("severe.patterns", "meteor");
        custom.setProperty("default.priority", "HIGH");
        FallbackTriage custom1 = new FallbackTriage(custom);

        assertThat(custom1.classify("banana", "x").priority()).isEqualTo(Priority.LOW);        // the rule's own priority
        assertThat(custom1.classify("apple", "x").priority()).isEqualTo(Priority.HIGH);        // no rule: the default
        assertThat(custom1.classify("banana", "meteor").priority()).isEqualTo(Priority.CRITICAL);
    }

    @Test
    void incompleteRulesFailFastWithAClearMessage() {
        assertThatThrownBy(() -> new FallbackTriage(new Properties()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fallback.reason");
    }
}
