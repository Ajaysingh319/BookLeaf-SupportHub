package com.bookleaf.support.ai;

import com.bookleaf.support.TestProperties;
import com.bookleaf.support.domain.Priority;
import com.bookleaf.support.domain.TicketCategory;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TriageParserTest {

    private final TriageParser parser = new TriageParser(JsonMapper.builder().build(), TestProperties.create());

    @Test
    void acceptsValidOutput() {
        TriageResult result = parser.parse(
                "{\"category\":\"Royalty & Payments\",\"priority\":\"High\",\"reason\":\"No royalty for six months.\"}");
        assertThat(result.category()).isEqualTo(TicketCategory.ROYALTY_PAYMENTS);
        assertThat(result.priority()).isEqualTo(Priority.HIGH);
        assertThat(result.reason()).isEqualTo("No royalty for six months.");
    }

    @Test
    void acceptsAllSixCategoriesAndFourPriorities() {
        for (TicketCategory category : TicketCategory.values()) {
            for (Priority priority : Priority.values()) {
                TriageResult result = parser.parse("{\"category\":\"" + category.label() + "\",\"priority\":\""
                        + priority.label() + "\",\"reason\":\"ok\"}");
                assertThat(result.category()).isEqualTo(category);
                assertThat(result.priority()).isEqualTo(priority);
            }
        }
    }

    @Test
    void acceptsJsonWrappedInACodeFence() {
        TriageResult result = parser.parse(
                "```json\n{\"category\":\"General Inquiry\",\"priority\":\"Low\",\"reason\":\"Bio update.\"}\n```");
        assertThat(result.category()).isEqualTo(TicketCategory.GENERAL_INQUIRY);
        assertThat(result.priority()).isEqualTo(Priority.LOW);
    }

    @Test
    void rejectsUnknownCategory() {
        assertInvalid("{\"category\":\"Billing\",\"priority\":\"High\",\"reason\":\"x\"}");
    }

    @Test
    void rejectsUnknownPriority() {
        assertInvalid("{\"category\":\"General Inquiry\",\"priority\":\"Urgent\",\"reason\":\"x\"}");
    }

    @Test
    void rejectsUnexpectedExtraFields() {
        assertInvalid("{\"category\":\"General Inquiry\",\"priority\":\"Low\",\"reason\":\"x\",\"confidence\":0.9}");
    }

    @Test
    void rejectsMissingFields() {
        assertInvalid("{\"category\":\"General Inquiry\",\"priority\":\"Low\"}");
    }

    @Test
    void rejectsNonStringValues() {
        assertInvalid("{\"category\":1,\"priority\":\"Low\",\"reason\":\"x\"}");
    }

    @Test
    void rejectsMalformedAndEmptyOutput() {
        assertInvalid("Sure! Here is the classification: Royalty");
        assertInvalid("");
        assertInvalid("[]");
        assertInvalid(null);
    }

    @Test
    void longReasonsAreTruncatedToTheConfiguredLength() {
        int limit = TestProperties.create().ai().triage().reasonMaxLength();
        TriageResult result = parser.parse("{\"category\":\"General Inquiry\",\"priority\":\"Low\",\"reason\":\""
                + "a".repeat(limit * 4) + "\"}");
        assertThat(result.reason()).hasSize(limit);
    }

    private void assertInvalid(String content) {
        assertThatThrownBy(() -> parser.parse(content))
                .isInstanceOf(AiUnavailableException.class)
                .extracting(ex -> ((AiUnavailableException) ex).reasonCode())
                .isEqualTo("invalid_output");
    }
}
