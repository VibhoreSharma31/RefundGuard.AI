package com.refundguard.sla;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * Pure, stateless SLA maths. Kept free of Spring so it can be unit tested and
 * reused from entity getters.
 *
 * RBI TAT convention: "T" is the calendar date the refund was initiated, and a
 * refund with a T+N turnaround must be credited by the end of calendar day T+N.
 */
public final class SlaCalculator {

    public static final String NOT_TRACKED = "NOT_TRACKED";
    public static final String ON_TRACK = "ON_TRACK";
    public static final String DUE_SOON = "DUE_SOON";
    public static final String BREACHED = "BREACHED";
    public static final String MET = "MET";
    public static final String MET_LATE = "MET_LATE";

    private SlaCalculator() {}

    /** Deadline = 23:59:59 on calendar day (T + tatDays). */
    public static LocalDateTime dueAt(LocalDateTime requestedAt, int tatDays) {
        return requestedAt.toLocalDate().plusDays(tatDays).atTime(23, 59, 59);
    }

    public static String state(LocalDateTime dueAt, LocalDateTime settledAt, LocalDateTime now) {
        if (dueAt == null) return NOT_TRACKED;
        if (settledAt != null) {
            return settledAt.toLocalDate().isAfter(dueAt.toLocalDate()) ? MET_LATE : MET;
        }
        if (now.isAfter(dueAt)) return BREACHED;
        return daysRemaining(dueAt, now) <= 1 ? DUE_SOON : ON_TRACK;
    }

    /** Whole calendar days past the deadline (0 if not late). */
    public static long daysOverdue(LocalDateTime dueAt, LocalDateTime settledAt, LocalDateTime now) {
        if (dueAt == null) return 0;
        LocalDate reference = settledAt != null ? settledAt.toLocalDate() : now.toLocalDate();
        long days = ChronoUnit.DAYS.between(dueAt.toLocalDate(), reference);
        return Math.max(0, days);
    }

    /** Whole calendar days left until the deadline day (0 = due today). */
    public static long daysRemaining(LocalDateTime dueAt, LocalDateTime now) {
        if (dueAt == null) return 0;
        return Math.max(0, ChronoUnit.DAYS.between(now.toLocalDate(), dueAt.toLocalDate()));
    }

    public static BigDecimal compensation(long daysOverdue, BigDecimal perDay) {
        if (perDay == null || daysOverdue <= 0) return BigDecimal.ZERO;
        return perDay.multiply(BigDecimal.valueOf(daysOverdue));
    }

    public static String label(LocalDateTime dueAt, LocalDateTime settledAt, LocalDateTime now) {
        String state = state(dueAt, settledAt, now);
        switch (state) {
            case NOT_TRACKED: return "No SLA";
            case MET: return "Settled on time";
            case MET_LATE: return "Settled " + plural(daysOverdue(dueAt, settledAt, now)) + " late";
            case BREACHED: {
                long d = daysOverdue(dueAt, null, now);
                return d == 0 ? "Overdue (deadline passed today)" : "Overdue by " + plural(d);
            }
            default: {
                long d = daysRemaining(dueAt, now);
                if (d == 0) return "Due today";
                if (d == 1) return "Due tomorrow";
                return "Due in " + d + " days";
            }
        }
    }

    private static String plural(long d) {
        return d + (d == 1 ? " day" : " days");
    }
}
