package com.bookleaf.support.domain;

import org.junit.jupiter.api.Test;

import static com.bookleaf.support.domain.TicketStatus.CLOSED;
import static com.bookleaf.support.domain.TicketStatus.IN_PROGRESS;
import static com.bookleaf.support.domain.TicketStatus.OPEN;
import static com.bookleaf.support.domain.TicketStatus.RESOLVED;
import static org.assertj.core.api.Assertions.assertThat;

/** TDD section 8: the status transition table. */
class TicketStatusRulesTest {

    @Test
    void openCanMoveToAnyOtherStatus() {
        assertThat(TicketStatusRules.canTransition(OPEN, IN_PROGRESS)).isTrue();
        assertThat(TicketStatusRules.canTransition(OPEN, RESOLVED)).isTrue();
        assertThat(TicketStatusRules.canTransition(OPEN, CLOSED)).isTrue();
    }

    @Test
    void inProgressCanMoveToAnyOtherStatus() {
        assertThat(TicketStatusRules.canTransition(IN_PROGRESS, OPEN)).isTrue();
        assertThat(TicketStatusRules.canTransition(IN_PROGRESS, RESOLVED)).isTrue();
        assertThat(TicketStatusRules.canTransition(IN_PROGRESS, CLOSED)).isTrue();
    }

    @Test
    void resolvedCanOnlyReopenOrClose() {
        assertThat(TicketStatusRules.canTransition(RESOLVED, OPEN)).isTrue();
        assertThat(TicketStatusRules.canTransition(RESOLVED, CLOSED)).isTrue();
        assertThat(TicketStatusRules.canTransition(RESOLVED, IN_PROGRESS)).isFalse();
    }

    @Test
    void closedCanOnlyReopen() {
        assertThat(TicketStatusRules.canTransition(CLOSED, OPEN)).isTrue();
        assertThat(TicketStatusRules.canTransition(CLOSED, IN_PROGRESS)).isFalse();
        assertThat(TicketStatusRules.canTransition(CLOSED, RESOLVED)).isFalse();
    }

    @Test
    void sameStatusIsAllowedAsANoOp() {
        for (TicketStatus status : TicketStatus.values()) {
            assertThat(TicketStatusRules.canTransition(status, status)).isTrue();
        }
    }

    @Test
    void onlyOpenAndInProgressAreUnresolved() {
        assertThat(TicketStatusRules.isUnresolved(OPEN)).isTrue();
        assertThat(TicketStatusRules.isUnresolved(IN_PROGRESS)).isTrue();
        assertThat(TicketStatusRules.isUnresolved(RESOLVED)).isFalse();
        assertThat(TicketStatusRules.isUnresolved(CLOSED)).isFalse();
    }
}
