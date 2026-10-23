package com.bookleaf.support.domain;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Allowed ticket status transitions (TDD section 8):
 * OPEN -> IN_PROGRESS, RESOLVED, CLOSED; IN_PROGRESS -> OPEN, RESOLVED, CLOSED;
 * RESOLVED -> OPEN, CLOSED; CLOSED -> OPEN. Setting the same status again is a no-op.
 */
public final class TicketStatusRules {

    private static final Map<TicketStatus, Set<TicketStatus>> ALLOWED = Map.of(
            TicketStatus.OPEN, EnumSet.of(TicketStatus.IN_PROGRESS, TicketStatus.RESOLVED, TicketStatus.CLOSED),
            TicketStatus.IN_PROGRESS, EnumSet.of(TicketStatus.OPEN, TicketStatus.RESOLVED, TicketStatus.CLOSED),
            TicketStatus.RESOLVED, EnumSet.of(TicketStatus.OPEN, TicketStatus.CLOSED),
            TicketStatus.CLOSED, EnumSet.of(TicketStatus.OPEN));

    private TicketStatusRules() {
    }

    public static boolean canTransition(TicketStatus from, TicketStatus to) {
        return from == to || ALLOWED.get(from).contains(to);
    }

    /** The statuses a ticket in this status may move to (not including itself). */
    public static Set<TicketStatus> allowedNext(TicketStatus from) {
        return ALLOWED.get(from);
    }

    public static boolean isUnresolved(TicketStatus status) {
        return status == TicketStatus.OPEN || status == TicketStatus.IN_PROGRESS;
    }
}
