package com.refundguard.risk;

import java.math.BigDecimal;

/**
 * Facts the scoring engine may use. Every field except refundId/amount is nullable:
 * {@code null} means "this data is not available" (never "zero"), and the engine then skips
 * the dependent rule and reports it under {@code notEvaluated} instead of inventing a value.
 * The refund's processing status is deliberately NOT an input: a status alone is never treated as risk.
 */
public record RiskInput(
        Long refundId,
        BigDecimal amount,
        String currency,
        /** Linked order record found? */
        boolean orderFound,
        BigDecimal orderAmount,
        String orderCurrency,
        /** Refund records that exist for the same order (including this one). */
        Integer refundsForOrder,
        /** refund.created events recorded for this refund (gateway attempts). */
        Integer createdEvents,
        /** Refunds (incl. this one) for the same customer e-mail inside the look-back window; null if no customer on file. */
        Integer customerRefundCountInWindow,
        /** Sum of those refunds' amounts. */
        BigDecimal customerRefundTotalInWindow,
        /** Open cases of type AMOUNT_MISMATCH / DUPLICATE_SETTLEMENT / REFUND_FAILED on this refund. */
        Integer openDiscrepancyCases,
        /** Sum of amounts of all non-rejected refunds for the same order (incl. this one); null if unknown. */
        BigDecimal refundsTotalForOrder,
        /** Amount the bank actually credited (rg_refunds.settled_amount); null = not settled yet (not "unknown"). */
        BigDecimal settledAmount,
        /** Distinct bank UTRs of settlement rows matched to this refund; null if not looked up. */
        Integer distinctSettlementUtrs,
        /** Subset of openDiscrepancyCases of type AMOUNT_MISMATCH (used to avoid double counting). */
        Integer openAmountMismatchCases,
        /** Subset of openDiscrepancyCases of type DUPLICATE_SETTLEMENT (used to avoid double counting). */
        Integer openDuplicateSettlementCases) {

    /** Original (V1) set of facts; settlement facts are "none / not settled". */
    public RiskInput(Long refundId, BigDecimal amount, String currency, boolean orderFound, BigDecimal orderAmount,
                     String orderCurrency, Integer refundsForOrder, Integer createdEvents, Integer customerRefundCountInWindow,
                     BigDecimal customerRefundTotalInWindow, Integer openDiscrepancyCases) {
        this(refundId, amount, currency, orderFound, orderAmount, orderCurrency, refundsForOrder, createdEvents,
                customerRefundCountInWindow, customerRefundTotalInWindow, openDiscrepancyCases, null, null, 0, 0, 0);
    }
}
