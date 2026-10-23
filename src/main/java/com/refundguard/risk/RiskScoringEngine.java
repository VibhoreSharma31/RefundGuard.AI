package com.refundguard.risk;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Rule-based, explainable refund-risk scoring (0-100).
 *
 * This is NOT a machine-learning model and NOT a fraud probability: it is a fixed set of transparent rules whose
 * weights and thresholds come from {@link RiskConfig}. The engine is a pure function of (input, config, assessedAt):
 * the same arguments always give the same result, and it never reads the clock or the database itself.
 *
 * Overlap rules (no double counting):
 *  - VERY_HIGH_AMOUNT replaces HIGH_AMOUNT; repeated-attempt and customer-frequency rules are tiered.
 *  - CUMULATIVE_EXCEEDS_ORDER replaces MULTIPLE_REFUNDS_FOR_ORDER and only applies when this refund alone does not
 *    already exceed the order (EXCEEDS_ORDER_AMOUNT).
 *  - An open AMOUNT_MISMATCH / DUPLICATE_SETTLEMENT case is not counted again under OPEN_DISCREPANCY when the
 *    settlement rule that describes the same fact (SETTLEMENT_AMOUNT_MISMATCH / DUPLICATE_SETTLEMENT) already fired.
 * The refund's processing status is never an input.
 */
public final class RiskScoringEngine {

    public static final String ENGINE_VERSION = "RULE_BASED_V2";

    public static final String HIGH_AMOUNT = "HIGH_AMOUNT";
    public static final String VERY_HIGH_AMOUNT = "VERY_HIGH_AMOUNT";
    public static final String EXCEEDS_ORDER_AMOUNT = "EXCEEDS_ORDER_AMOUNT";
    public static final String ORDER_CURRENCY_MISMATCH = "ORDER_CURRENCY_MISMATCH";
    public static final String MULTIPLE_REFUNDS_FOR_ORDER = "MULTIPLE_REFUNDS_FOR_ORDER";
    public static final String CUMULATIVE_EXCEEDS_ORDER = "CUMULATIVE_EXCEEDS_ORDER";
    public static final String ORDER_NOT_FOUND = "ORDER_NOT_FOUND";
    public static final String REPEATED_ATTEMPTS = "REPEATED_ATTEMPTS";
    public static final String CUSTOMER_REFUND_FREQUENCY = "CUSTOMER_REFUND_FREQUENCY";
    public static final String CUSTOMER_REFUND_TOTAL = "CUSTOMER_REFUND_TOTAL";
    public static final String SETTLEMENT_AMOUNT_MISMATCH = "SETTLEMENT_AMOUNT_MISMATCH";
    public static final String DUPLICATE_SETTLEMENT = "DUPLICATE_SETTLEMENT";
    public static final String OPEN_DISCREPANCY = "OPEN_DISCREPANCY";

    private final RiskConfig cfg;

    public RiskScoringEngine(RiskConfig cfg) {
        this.cfg = cfg;
    }

    public RiskResult assess(RiskInput in, LocalDateTime assessedAt) {
        List<RiskIndicator> hits = new ArrayList<>();
        List<String> skipped = new ArrayList<>();

        // 1. Refund amount (tiered: the very-high tier replaces the high tier).
        if (in.amount() == null) {
            skipped.add("Refund amount is missing: amount rules were not evaluated.");
        } else if (in.amount().compareTo(cfg.getVeryHighAmount()) >= 0) {
            add(hits, VERY_HIGH_AMOUNT, cfg.getVeryHighAmountWeight(),
                    "Refund amount " + money(in.amount()) + " is at or above the very-high threshold " + money(cfg.getVeryHighAmount()),
                    ev("refundAmount", money(in.amount()), "threshold", money(cfg.getVeryHighAmount())), false);
        } else if (in.amount().compareTo(cfg.getHighAmount()) >= 0) {
            add(hits, HIGH_AMOUNT, cfg.getHighAmountWeight(),
                    "Refund amount " + money(in.amount()) + " is at or above the high-amount threshold " + money(cfg.getHighAmount()),
                    ev("refundAmount", money(in.amount()), "threshold", money(cfg.getHighAmount())), false);
        }

        // 2. Order linkage and refund-vs-order consistency.
        boolean exceedsOrder = false;
        boolean sameCurrencyAsOrder = false;
        if (!in.orderFound()) {
            add(hits, ORDER_NOT_FOUND, cfg.getOrderNotFoundWeight(), "No matching order record exists for this refund",
                    ev("orderRecord", "not found"), false);
            skipped.add("Order record not found: refund-vs-order amount and customer-pattern rules were not evaluated.");
        } else {
            boolean currenciesKnown = in.currency() != null && in.orderCurrency() != null;
            sameCurrencyAsOrder = currenciesKnown && in.currency().equalsIgnoreCase(in.orderCurrency());
            if (currenciesKnown && !sameCurrencyAsOrder) {
                add(hits, ORDER_CURRENCY_MISMATCH, cfg.getOrderCurrencyMismatchWeight(),
                        "Refund currency " + in.currency() + " differs from the order (payment) currency " + in.orderCurrency(),
                        ev("refundCurrency", in.currency(), "orderCurrency", in.orderCurrency()), true);
            }
            if (in.orderAmount() == null || in.amount() == null) {
                skipped.add("Order amount unavailable: refund-vs-order comparison was not evaluated.");
            } else if (currenciesKnown && !sameCurrencyAsOrder) {
                skipped.add("Refund and order currencies differ (" + in.currency() + " vs " + in.orderCurrency()
                        + "): refund-vs-order comparison was not evaluated.");
            } else if (in.amount().compareTo(in.orderAmount()) > 0) {
                exceedsOrder = true;
                add(hits, EXCEEDS_ORDER_AMOUNT, cfg.getExceedsOrderWeight(),
                        "Refund amount " + money(in.amount()) + " exceeds the original order amount " + money(in.orderAmount()),
                        ev("refundAmount", money(in.amount()), "orderAmount", money(in.orderAmount()),
                                "excess", money(in.amount().subtract(in.orderAmount()))), true);
            }
            if (in.customerRefundCountInWindow() == null) {
                skipped.add("No customer e-mail on the order: customer-pattern rules were not evaluated.");
            } else {
                int n = in.customerRefundCountInWindow();
                Map<String, String> e = ev("customerRefundsInWindow", String.valueOf(n), "windowDays", String.valueOf(cfg.getCustomerWindowDays()));
                if (n >= cfg.getCustomerFrequencyHighThreshold()) {
                    add(hits, CUSTOMER_REFUND_FREQUENCY, cfg.getCustomerFrequencyHighWeight(),
                            "Customer has " + n + " refunds in the last " + cfg.getCustomerWindowDays()
                                    + " day(s) (high-frequency threshold " + cfg.getCustomerFrequencyHighThreshold() + ")", e, false);
                } else if (n >= cfg.getCustomerFrequencyThreshold()) {
                    add(hits, CUSTOMER_REFUND_FREQUENCY, cfg.getCustomerFrequencyWeight(),
                            "Customer has " + n + " refunds in the last " + cfg.getCustomerWindowDays()
                                    + " day(s) (threshold " + cfg.getCustomerFrequencyThreshold() + ")", e, false);
                }
                BigDecimal total = in.customerRefundTotalInWindow();
                if (total != null && total.compareTo(cfg.getCustomerWindowTotal()) >= 0) {
                    add(hits, CUSTOMER_REFUND_TOTAL, cfg.getCustomerWindowTotalWeight(),
                            "Customer refunds total " + money(total) + " in the last " + cfg.getCustomerWindowDays()
                                    + " day(s) (threshold " + money(cfg.getCustomerWindowTotal()) + ")",
                            ev("customerRefundTotal", money(total), "threshold", money(cfg.getCustomerWindowTotal()),
                                    "windowDays", String.valueOf(cfg.getCustomerWindowDays())), false);
                }
            }
        }

        // 3. Several refunds for one order: cumulative excess over the order replaces the plain "multiple refunds" rule.
        if (in.refundsForOrder() != null && in.refundsForOrder() >= 2) {
            boolean cumulative = !exceedsOrder && sameCurrencyAsOrder && in.orderAmount() != null
                    && in.refundsTotalForOrder() != null && in.refundsTotalForOrder().compareTo(in.orderAmount()) > 0;
            if (cumulative) {
                add(hits, CUMULATIVE_EXCEEDS_ORDER, cfg.getCumulativeExceedsOrderWeight(),
                        in.refundsForOrder() + " refunds for this order total " + money(in.refundsTotalForOrder())
                                + ", more than the order amount " + money(in.orderAmount()),
                        ev("refundsForOrder", String.valueOf(in.refundsForOrder()), "refundsTotal", money(in.refundsTotalForOrder()),
                                "orderAmount", money(in.orderAmount()),
                                "excess", money(in.refundsTotalForOrder().subtract(in.orderAmount()))), true);
            } else {
                add(hits, MULTIPLE_REFUNDS_FOR_ORDER, cfg.getMultipleRefundsForOrderWeight(),
                        in.refundsForOrder() + " refund records exist for the same order",
                        ev("refundsForOrder", String.valueOf(in.refundsForOrder())), false);
                if (in.refundsTotalForOrder() == null && in.orderAmount() != null) {
                    skipped.add("Total of refunds for this order unavailable: cumulative refundable-amount rule was not evaluated.");
                }
            }
        }

        // 4. Repeated gateway attempts for this refund (tiered).
        if (in.createdEvents() == null) {
            skipped.add("Gateway event history unavailable: repeated-attempt rule was not evaluated.");
        } else if (in.createdEvents() >= cfg.getRepeatedAttemptsHighThreshold()) {
            add(hits, REPEATED_ATTEMPTS, cfg.getRepeatedAttemptsHighWeight(),
                    "Gateway reported " + in.createdEvents() + " refund-created events for this refund (high threshold "
                            + cfg.getRepeatedAttemptsHighThreshold() + ")",
                    ev("refundCreatedEvents", String.valueOf(in.createdEvents())), false);
        } else if (in.createdEvents() >= cfg.getRepeatedAttemptsThreshold()) {
            add(hits, REPEATED_ATTEMPTS, cfg.getRepeatedAttemptsWeight(),
                    "Gateway reported " + in.createdEvents() + " refund-created events for this refund (threshold "
                            + cfg.getRepeatedAttemptsThreshold() + ")",
                    ev("refundCreatedEvents", String.valueOf(in.createdEvents())), false);
        }

        // 5. Bank settlement vs refund (only once the bank has settled; "not settled" is not risk).
        boolean settlementMismatch = false;
        if (in.settledAmount() != null && in.amount() != null) {
            int cmp = in.settledAmount().compareTo(in.amount());
            Map<String, String> e = ev("refundAmount", money(in.amount()), "settledAmount", money(in.settledAmount()),
                    "difference", money(in.settledAmount().subtract(in.amount()).abs()));
            if (cmp > 0) {
                settlementMismatch = add(hits, SETTLEMENT_AMOUNT_MISMATCH, cfg.getSettlementOverCreditWeight(),
                        "Bank credited " + money(in.settledAmount()) + ", MORE than the refund amount " + money(in.amount())
                                + " (possible over-refund)", e, false);
            } else if (cmp < 0) {
                settlementMismatch = add(hits, SETTLEMENT_AMOUNT_MISMATCH, cfg.getSettlementShortfallWeight(),
                        "Bank credited " + money(in.settledAmount()) + ", less than the refund amount " + money(in.amount())
                                + " (often operational: partial credit or charges)", e, false);
            }
        }
        boolean duplicateSettlement = false;
        if (in.distinctSettlementUtrs() != null && in.distinctSettlementUtrs() >= 2) {
            duplicateSettlement = add(hits, DUPLICATE_SETTLEMENT, cfg.getDuplicateSettlementWeight(),
                    "Bank settlement shows " + in.distinctSettlementUtrs() + " different UTRs credited for this refund (possible double credit)",
                    ev("distinctSettlementUtrs", String.valueOf(in.distinctSettlementUtrs())), false);
        }

        // 6. Open discrepancy cases, minus those already described by a settlement rule above.
        if (in.openDiscrepancyCases() != null && in.openDiscrepancyCases() > 0) {
            int open = in.openDiscrepancyCases();
            if (settlementMismatch && in.openAmountMismatchCases() != null) open -= in.openAmountMismatchCases();
            if (duplicateSettlement && in.openDuplicateSettlementCases() != null) open -= in.openDuplicateSettlementCases();
            if (open > 0) {
                add(hits, OPEN_DISCREPANCY, cfg.getOpenDiscrepancyWeight(),
                        open + " open discrepancy case(s) (amount mismatch / duplicate settlement / failed refund) on this refund",
                        ev("openDiscrepancyCases", String.valueOf(open)), false);
            }
        }

        int raw = hits.stream().mapToInt(RiskIndicator::points).sum();
        int score = Math.max(0, Math.min(100, raw));
        return new RiskResult(score, cfg.levelFor(score), assessedAt, List.copyOf(hits), List.copyOf(skipped),
                fingerprint(in), ENGINE_VERSION);
    }

    /** Fingerprint of the input values and the configuration; used to detect outdated assessments and duplicates. */
    public String fingerprint(RiskInput in) {
        String canonical = String.join("|",
                String.valueOf(in.refundId()), RiskConfig.str(in.amount()), String.valueOf(in.currency()),
                String.valueOf(in.orderFound()), RiskConfig.str(in.orderAmount()), String.valueOf(in.orderCurrency()),
                String.valueOf(in.refundsForOrder()), String.valueOf(in.createdEvents()),
                String.valueOf(in.customerRefundCountInWindow()), RiskConfig.str(in.customerRefundTotalInWindow()),
                String.valueOf(in.openDiscrepancyCases()), RiskConfig.str(in.refundsTotalForOrder()),
                RiskConfig.str(in.settledAmount()), String.valueOf(in.distinctSettlementUtrs()),
                String.valueOf(in.openAmountMismatchCases()), String.valueOf(in.openDuplicateSettlementCases()),
                cfg.fingerprint(), ENGINE_VERSION);
        return RiskConfig.sha256(canonical).substring(0, 32);
    }

    /** Adds the indicator when its weight is positive; returns whether it was added. */
    private static boolean add(List<RiskIndicator> hits, String code, int points, String reason,
                               Map<String, String> evidence, boolean policyViolation) {
        if (points <= 0) return false;
        hits.add(new RiskIndicator(code, points, reason, evidence, policyViolation));
        return true;
    }

    private static Map<String, String> ev(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1] == null ? "unavailable" : kv[i + 1]);
        return m;
    }

    private static String money(BigDecimal v) {
        return "₹" + v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
