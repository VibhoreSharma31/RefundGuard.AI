package com.refundguard.ai;

import com.refundguard.model.Refund;
import com.refundguard.model.RefundStatus;
import com.refundguard.model.SettlementRow;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic root-cause analyser. Always available (no API key needed) and
 * used as the first-pass hypothesis on every case, and as the fallback when the
 * Claude analysis is disabled or fails.
 *
 * Unlike the old fixed strings, every hypothesis is built from the case's real
 * numbers: amounts, payment method, bank, deadline and days overdue.
 */
public final class RuleBasedRootCauseAnalyzer {

    public record Analysis(String hypothesis, String recommendedAction, String customerMessage) {}

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH);
    private static final Pattern BANK_IN_UTR = Pattern.compile("^UTR[_-]?([A-Z]{3,6})[_-]");

    private RuleBasedRootCauseAnalyzer() {}

    public static Analysis analyze(String issueType, Refund refund, SettlementRow row) {
        switch (issueType) {
            case "AMOUNT_MISMATCH": return amountMismatch(refund, row);
            case "SLA_BREACH": return slaBreach(refund);
            case "REFUND_FAILED": return refundFailed(refund);
            case "UNMATCHED_SETTLEMENT": return unmatchedSettlement(row);
            case "DUPLICATE_SETTLEMENT": return duplicateSettlement(refund, row);
            default: return new Analysis(
                    "Discrepancy detected; not enough data for an automatic diagnosis.",
                    "Review the refund timeline and the latest settlement file manually.",
                    null);
        }
    }

    static Analysis amountMismatch(Refund refund, SettlementRow row) {
        BigDecimal expected = refund.getAmount();
        BigDecimal credited = row != null ? row.getAmount() : refund.getSettledAmount();
        if (credited == null) credited = BigDecimal.ZERO;
        BigDecimal diff = expected.subtract(credited);
        BigDecimal pct = expected.signum() == 0 ? BigDecimal.ZERO
                : diff.abs().multiply(BigDecimal.valueOf(100)).divide(expected, 2, RoundingMode.HALF_UP);
        String bank = bankFromUtr(row != null ? row.getUtrNumber() : refund.getBankUtr());
        String via = bank != null ? " via " + bank : "";

        String hypothesis;
        String action;
        if (diff.signum() < 0) {
            hypothesis = "Over-credit: bank" + via + " credited " + inr(credited) + " but only " + inr(expected)
                    + " was refunded (excess " + inr(diff.abs()) + "). Likely a duplicate or wrongly keyed settlement - this is a direct loss risk.";
            action = "1) Freeze further refunds on order " + refund.getOrderId() + ". 2) Ask the gateway for the settlement breakup of UTR "
                    + nz(row != null ? row.getUtrNumber() : refund.getBankUtr()) + ". 3) Raise a recovery/reversal request for the excess.";
        } else if (credited.multiply(BigDecimal.valueOf(2)).compareTo(expected) == 0) {
            hypothesis = "Exactly 50% of the refund (" + inr(credited) + " of " + inr(expected) + ") was credited" + via
                    + ". This pattern usually means a partial refund was issued or the refund was split into two settlements.";
            action = "1) Check the gateway dashboard for a second refund/settlement on " + refund.getOrderId()
                    + ". 2) If none exists, confirm with the merchant whether a partial refund was intended.";
        } else if (pct.compareTo(new BigDecimal("1.5")) >= 0 && pct.compareTo(new BigDecimal("3.0")) <= 0) {
            hypothesis = "Short-credit of " + inr(diff) + " (" + pct + "%) matches typical gateway MDR/fee + GST deduction" + via
                    + ". Refunds to customers must be for the full amount; the fee should not be passed on.";
            action = "1) Raise a ticket with the payment gateway citing UTR " + nz(row != null ? row.getUtrNumber() : refund.getBankUtr())
                    + ". 2) Credit the " + inr(diff) + " shortfall to the customer from the merchant account if the gateway will not.";
        } else {
            hypothesis = "Bank" + via + " credited " + inr(credited) + " against an expected " + inr(expected)
                    + " (short by " + inr(diff) + ", " + pct + "%). Does not match a fee or partial-refund pattern; possible manual adjustment or data-entry error.";
            action = "1) Compare the gateway refund record with the bank statement line. 2) If the gateway shows the full amount, escalate to the acquirer bank with the UTR.";
        }
        String customer = "We have detected that part of your refund for order " + refund.getOrderId()
                + " has not reached you yet. Our team is fixing this and will update you within 2 working days.";
        return new Analysis(hypothesis, action, customer);
    }

    static Analysis slaBreach(Refund refund) {
        String method = nz(refund.getPaymentMethod());
        String due = refund.getSlaDueAt() != null ? refund.getSlaDueAt().format(DATE) : "the deadline";
        long late = refund.getDaysOverdue();
        String stage;
        String action;
        // The derived status becomes SLA_BREACHED once late, so use the last gateway event to locate the hold-up.
        String gw = refund.getGatewayStage();
        if (gw == null) {
            gw = switch (refund.getDerivedStatus() == null ? RefundStatus.REQUESTED : refund.getDerivedStatus()) {
                case GATEWAY_PENDING -> "refund.created";
                case GATEWAY_PROCESSED, POTENTIAL_DELAY -> "refund.processed";
                case FAILED_REPORTED -> "refund.failed";
                default -> "none";
            };
        }
        if ("none".equals(gw)) {
            stage = "The refund was requested but the gateway never acknowledged it (no refund.created webhook) - it is stuck inside the merchant system.";
            action = "1) Check whether the refund API call to the gateway failed or was never sent. 2) Re-initiate the refund immediately.";
        } else if ("refund.created".equals(gw)) {
            stage = "The gateway accepted the refund but never marked it processed - it is stuck at the gateway/acquirer.";
            action = "1) Escalate to the gateway support with refund id " + nz(refund.getProviderRefundId()) + ". 2) Ask for the ARN/RRN and expected credit date.";
        } else if ("refund.failed".equals(gw)) {
            stage = "The gateway reported the refund as FAILED and it has not been re-initiated, so the customer still has not been paid.";
            action = "1) Collect alternate bank details from the customer. 2) Re-initiate via IMPS/NEFT today to stop compensation accruing.";
        } else {
            stage = "The gateway marked the refund processed, but no matching credit appears in the bank settlement files - the money is in transit between the acquirer and the customer's bank.";
            action = "1) Get the ARN/RRN from the gateway for refund " + nz(refund.getProviderRefundId())
                    + ". 2) Share it with the customer so they can trace it with their bank. 3) Raise a dispute with the acquirer bank if the ARN is missing.";
        }
        String hypothesis = method + " refund of " + inr(refund.getAmount()) + " missed its T+" + nz(refund.getSlaTatDays())
                + " deadline (" + due + ") and is " + late + " day(s) overdue. " + stage
                + " Estimated compensation exposure so far: " + inr(refund.getCompensationDue()) + ".";
        String customer = "Your refund of " + inr(refund.getAmount()) + " for order " + refund.getOrderId()
                + " is taking longer than expected. We have escalated it to our payment partner and will share a bank reference number with you shortly.";
        return new Analysis(hypothesis, action, customer);
    }

    static Analysis refundFailed(Refund refund) {
        String method = nz(refund.getPaymentMethod()).toUpperCase(Locale.ROOT);
        String reason;
        if (method.contains("UPI")) {
            reason = "UPI refunds usually fail when the customer's VPA/bank account is closed or frozen, or the remitter bank declined the credit.";
        } else if (method.contains("CARD")) {
            reason = "Card refunds usually fail when the card was closed, replaced or expired after the purchase.";
        } else if (method.contains("WALLET")) {
            reason = "Wallet refunds usually fail when the wallet is closed, KYC-expired or over its balance limit.";
        } else {
            reason = "Bank-transfer refunds usually fail because of a closed account or an incorrect account/IFSC.";
        }
        String hypothesis = "The gateway reported refund " + nz(refund.getProviderRefundId()) + " of " + inr(refund.getAmount())
                + " as FAILED. " + reason + " The customer has NOT received the money and the SLA clock is still running.";
        String action = "1) Contact the customer to collect alternate bank details. 2) Re-initiate the refund via bank transfer (IMPS/NEFT). 3) Close this case only after the new refund settles.";
        String customer = "Your refund for order " + refund.getOrderId() + " could not be credited to the original payment method. "
                + "Please share alternate bank account details so we can send it to you directly.";
        return new Analysis(hypothesis, action, customer);
    }

    static Analysis unmatchedSettlement(SettlementRow row) {
        String bank = bankFromUtr(row.getUtrNumber());
        String hypothesis = "The bank settlement file shows a credit of " + inr(row.getAmount())
                + (bank != null ? " via " + bank : "") + " (UTR " + nz(row.getUtrNumber()) + ", reference '" + nz(row.getRefundReference())
                + "') but RefundGuard has no refund with that reference. Possible causes: refund issued manually from the gateway dashboard, "
                + "a mistyped reference, or money sent to the wrong beneficiary.";
        String action = "1) Search the gateway dashboard for reference " + nz(row.getRefundReference())
                + ". 2) If it is a legitimate manual refund, import it so it is tracked. 3) If no refund exists, raise a recall/reversal with the bank using the UTR.";
        return new Analysis(hypothesis, action, null);
    }

    static Analysis duplicateSettlement(Refund refund, SettlementRow row) {
        String hypothesis = "Refund for order " + refund.getOrderId() + " was already settled (UTR " + nz(refund.getBankUtr())
                + ") but another settlement of " + inr(row.getAmount()) + " arrived with UTR " + nz(row.getUtrNumber())
                + ". The customer may have been refunded twice.";
        String action = "1) Confirm both UTRs with the bank. 2) If both credits are real, raise a recovery request for the duplicate amount.";
        return new Analysis(hypothesis, action, null);
    }

    static String bankFromUtr(String utr) {
        if (utr == null) return null;
        Matcher m = BANK_IN_UTR.matcher(utr.toUpperCase(Locale.ROOT));
        return m.find() ? m.group(1) : null;
    }

    static String inr(BigDecimal v) {
        if (v == null) return "₹0";
        return "₹" + v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String nz(Object o) {
        return o == null ? "N/A" : o.toString();
    }
}
