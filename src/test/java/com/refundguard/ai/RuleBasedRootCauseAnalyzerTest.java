package com.refundguard.ai;

import com.refundguard.model.Refund;
import com.refundguard.model.RefundStatus;
import com.refundguard.model.SettlementRow;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class RuleBasedRootCauseAnalyzerTest {

    private static Refund refund(String amount, String method) {
        Refund r = new Refund();
        r.setId(1L);
        r.setOrderId("ORD-9003");
        r.setProviderRefundId("rfnd_ord9003");
        r.setAmount(new BigDecimal(amount));
        r.setPaymentMethod(method);
        r.setDerivedStatus(RefundStatus.GATEWAY_PROCESSED);
        return r;
    }

    private static SettlementRow row(String amount, String utr) {
        SettlementRow s = new SettlementRow();
        s.setId(10L);
        s.setRefundReference("rfnd_ord9003");
        s.setUtrNumber(utr);
        s.setAmount(new BigDecimal(amount));
        s.setSettlementDate(LocalDate.of(2026, 10, 13));
        return s;
    }

    @Test
    void detectsGatewayFeeDeduction() {
        var a = RuleBasedRootCauseAnalyzer.analyze("AMOUNT_MISMATCH", refund("1000.00", "UPI"), row("980.00", "UTR_HDFC_1"));
        assertThat(a.hypothesis()).contains("MDR").contains("HDFC").contains("₹20.00");
    }

    @Test
    void detectsHalfRefund() {
        var a = RuleBasedRootCauseAnalyzer.analyze("AMOUNT_MISMATCH", refund("1200.00", "UPI"), row("600.00", "UTR_ICICI_1"));
        assertThat(a.hypothesis()).contains("50%");
    }

    @Test
    void detectsOverCredit() {
        var a = RuleBasedRootCauseAnalyzer.analyze("AMOUNT_MISMATCH", refund("1200.00", "UPI"), row("1500.00", "UTR_SBI_1"));
        assertThat(a.hypothesis()).startsWith("Over-credit");
    }

    @Test
    void slaBreachMentionsDaysAndCompensation() {
        Refund r = refund("5000.00", "UPI");
        r.setSlaTatDays(1);
        r.setRequestedAt(LocalDateTime.now().minusDays(4));
        r.setSlaDueAt(LocalDateTime.now().minusDays(3).withHour(23).withMinute(59));
        r.setCompensationPerDay(new BigDecimal("100"));
        var a = RuleBasedRootCauseAnalyzer.analyze("SLA_BREACH", r, null);
        assertThat(a.hypothesis()).contains("T+1").contains("3 day(s) overdue").contains("₹300.00");
    }

    @Test
    void unmatchedSettlementUsesRowFacts() {
        var a = RuleBasedRootCauseAnalyzer.analyze("UNMATCHED_SETTLEMENT", null, row("1500.00", "UTR_SBI_99887766"));
        assertThat(a.hypothesis()).contains("UTR_SBI_99887766").contains("no refund");
    }

    @Test
    void failedCardRefundExplainsCause() {
        var a = RuleBasedRootCauseAnalyzer.analyze("REFUND_FAILED", refund("2499.00", "CARD"), null);
        assertThat(a.hypothesis()).contains("card was closed");
        assertThat(a.customerMessage()).contains("alternate bank account");
    }
}
