package com.refundguard.sla;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class SlaCalculatorTest {

    private static final LocalDateTime REQUESTED = LocalDateTime.of(2026, 10, 10, 15, 30);

    @Test
    void deadlineIsEndOfDayTPlusN() {
        assertThat(SlaCalculator.dueAt(REQUESTED, 1)).isEqualTo(LocalDateTime.of(2026, 10, 11, 23, 59, 59));
        assertThat(SlaCalculator.dueAt(REQUESTED, 5)).isEqualTo(LocalDateTime.of(2026, 10, 15, 23, 59, 59));
    }

    @Test
    void freshRefundIsNotFlagged() {
        LocalDateTime due = SlaCalculator.dueAt(REQUESTED, 5);
        LocalDateTime now = REQUESTED.plusMinutes(1);
        assertThat(SlaCalculator.state(due, null, now)).isEqualTo(SlaCalculator.ON_TRACK);
        assertThat(SlaCalculator.label(due, null, now)).isEqualTo("Due in 5 days");
        assertThat(SlaCalculator.daysOverdue(due, null, now)).isZero();
    }

    @Test
    void dueSoonOnLastTwoDays() {
        LocalDateTime due = SlaCalculator.dueAt(REQUESTED, 1);
        assertThat(SlaCalculator.state(due, null, REQUESTED)).isEqualTo(SlaCalculator.DUE_SOON);
        assertThat(SlaCalculator.label(due, null, REQUESTED)).isEqualTo("Due tomorrow");
        assertThat(SlaCalculator.label(due, null, REQUESTED.plusDays(1))).isEqualTo("Due today");
    }

    @Test
    void breachCountsDaysAndCompensation() {
        LocalDateTime due = SlaCalculator.dueAt(REQUESTED, 1);          // 11 Oct 23:59:59
        LocalDateTime now = LocalDateTime.of(2026, 10, 14, 9, 0);       // 3 days later
        assertThat(SlaCalculator.state(due, null, now)).isEqualTo(SlaCalculator.BREACHED);
        assertThat(SlaCalculator.daysOverdue(due, null, now)).isEqualTo(3);
        assertThat(SlaCalculator.label(due, null, now)).isEqualTo("Overdue by 3 days");
        assertThat(SlaCalculator.compensation(3, new BigDecimal("100"))).isEqualByComparingTo("300");
    }

    @Test
    void settledLateAndOnTime() {
        LocalDateTime due = SlaCalculator.dueAt(REQUESTED, 1);
        LocalDateTime now = LocalDateTime.of(2026, 10, 20, 9, 0);
        assertThat(SlaCalculator.state(due, LocalDateTime.of(2026, 10, 11, 0, 0), now)).isEqualTo(SlaCalculator.MET);
        assertThat(SlaCalculator.state(due, LocalDateTime.of(2026, 10, 13, 0, 0), now)).isEqualTo(SlaCalculator.MET_LATE);
        // Compensation stops accruing once the money arrives.
        assertThat(SlaCalculator.daysOverdue(due, LocalDateTime.of(2026, 10, 13, 0, 0), now)).isEqualTo(2);
        assertThat(SlaCalculator.label(due, LocalDateTime.of(2026, 10, 13, 0, 0), now)).isEqualTo("Settled 2 days late");
    }

    @Test
    void policyLookupIsCaseAndPunctuationInsensitive() {
        SlaPolicyProperties p = new SlaPolicyProperties();
        p.getTatDays().put("upi", 1);
        p.getTatDays().put("netbanking", 5);
        assertThat(p.tatDaysFor("UPI")).isEqualTo(1);
        assertThat(p.tatDaysFor("Net_Banking")).isEqualTo(5);
        assertThat(p.tatDaysFor("SOMETHING_NEW")).isEqualTo(5); // default
        assertThat(p.compensationPerDayFor("UPI")).isEqualByComparingTo("100");
    }
}
