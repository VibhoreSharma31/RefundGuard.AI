package com.refundguard.risk;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Weights and thresholds of the rule-based risk engine. Plain JavaBean so it can be bound from
 * {@code refundguard.risk.*} properties (see {@link RiskSettings}) and constructed directly in unit tests.
 */
public class RiskConfig {

    // ── amount rules ──
    private BigDecimal highAmount = new BigDecimal("25000");
    private int highAmountWeight = 20;
    private BigDecimal veryHighAmount = new BigDecimal("100000");
    private int veryHighAmountWeight = 35;

    // ── data-consistency rules ──
    private int exceedsOrderWeight = 40;
    private int multipleRefundsForOrderWeight = 30;
    /** Missing order data is reported as unavailable, not scored as suspicious (set > 0 only if your policy says otherwise). */
    private int orderNotFoundWeight = 0;
    private int openDiscrepancyWeight = 15;
    /** Several refunds for one order whose total exceeds the order amount (replaces the multiple-refunds rule). */
    private int cumulativeExceedsOrderWeight = 40;
    /** Refund currency differs from the order (payment) currency. */
    private int orderCurrencyMismatchWeight = 30;

    // ── settlement rules (bank settlement data already reconciled into rg_refunds / rg_settlement_rows) ──
    /** Bank credited MORE than the refund amount (possible over-refund). */
    private int settlementOverCreditWeight = 45;
    /** Bank credited LESS than the refund amount (often operational, e.g. partial credit or fees). */
    private int settlementShortfallWeight = 20;
    /** Two or more distinct bank UTRs credited for the same refund. */
    private int duplicateSettlementWeight = 40;

    // ── repeated gateway attempts ──
    private int repeatedAttemptsThreshold = 2;
    private int repeatedAttemptsWeight = 15;
    private int repeatedAttemptsHighThreshold = 3;
    private int repeatedAttemptsHighWeight = 25;

    // ── customer pattern rules (same e-mail on linked orders) ──
    private int customerWindowDays = 7;
    private int customerFrequencyThreshold = 3;
    private int customerFrequencyWeight = 20;
    private int customerFrequencyHighThreshold = 5;
    private int customerFrequencyHighWeight = 30;
    private BigDecimal customerWindowTotal = new BigDecimal("100000");
    private int customerWindowTotalWeight = 15;

    // ── category boundaries (inclusive lower bounds) ──
    private int mediumMin = 30;
    private int highMin = 60;
    private int criticalMin = 80;

    // ── hold & staleness ──
    private boolean holdEnabled = true;
    private int holdMinScore = 50;
    private int maxAgeMinutes = 1440;
    /** Policy violations (refund above refundable amount, currency mismatch) force a hold whatever the score. */
    private boolean holdOnPolicyViolation = true;
    /** A manual reassessment with unchanged inputs inside this window reuses the latest assessment (no duplicate rows). */
    private int reassessDedupeSeconds = 60;

    /** Throws if the configuration is inconsistent. Called at startup and in tests. */
    public void validate() {
        require(0 < mediumMin && mediumMin < highMin && highMin < criticalMin && criticalMin <= 100,
                "risk thresholds must satisfy 0 < medium < high < critical <= 100");
        require(holdMinScore >= 1 && holdMinScore <= 100, "hold-min-score must be within 1..100");
        require(highAmount != null && veryHighAmount != null && highAmount.signum() > 0 && veryHighAmount.compareTo(highAmount) > 0,
                "very-high-amount must be greater than high-amount, both positive");
        require(customerWindowTotal != null && customerWindowTotal.signum() > 0, "customer-window-total must be positive");
        require(repeatedAttemptsThreshold >= 2 && repeatedAttemptsHighThreshold > repeatedAttemptsThreshold,
                "repeated-attempts thresholds must be >=2 and increasing");
        require(customerFrequencyThreshold >= 2 && customerFrequencyHighThreshold > customerFrequencyThreshold,
                "customer-frequency thresholds must be >=2 and increasing");
        require(customerWindowDays >= 1 && maxAgeMinutes >= 1, "window days and max age must be positive");
        require(reassessDedupeSeconds >= 0, "reassess-dedupe-seconds must not be negative");
        for (int w : new int[]{highAmountWeight, veryHighAmountWeight, exceedsOrderWeight, multipleRefundsForOrderWeight,
                orderNotFoundWeight, openDiscrepancyWeight, repeatedAttemptsWeight, repeatedAttemptsHighWeight,
                customerFrequencyWeight, customerFrequencyHighWeight, customerWindowTotalWeight, cumulativeExceedsOrderWeight,
                orderCurrencyMismatchWeight, settlementOverCreditWeight, settlementShortfallWeight, duplicateSettlementWeight}) {
            require(w >= 0 && w <= 100, "every weight must be within 0..100");
        }
    }

    private static void require(boolean ok, String msg) {
        if (!ok) throw new IllegalStateException("Invalid refundguard.risk configuration: " + msg);
    }

    public RiskLevel levelFor(int score) {
        if (score >= criticalMin) return RiskLevel.CRITICAL;
        if (score >= highMin) return RiskLevel.HIGH;
        if (score >= mediumMin) return RiskLevel.MEDIUM;
        return RiskLevel.LOW;
    }

    /** Hold criterion: a refund is held when holding is enabled and score >= holdMinScore. */
    public boolean shouldHold(int score) {
        return holdEnabled && score >= holdMinScore;
    }

    /** Full hold policy: score criterion, or a policy violation when hold-on-policy-violation is enabled. */
    public boolean wouldHold(int score, boolean policyViolation) {
        return shouldHold(score) || (holdEnabled && holdOnPolicyViolation && policyViolation);
    }

    /** Stable fingerprint of every value that influences a score; a change makes old assessments stale. */
    public String fingerprint() {
        String s = String.join("|", str(highAmount), "" + highAmountWeight, str(veryHighAmount), "" + veryHighAmountWeight,
                "" + exceedsOrderWeight, "" + multipleRefundsForOrderWeight, "" + orderNotFoundWeight, "" + openDiscrepancyWeight,
                "" + repeatedAttemptsThreshold, "" + repeatedAttemptsWeight, "" + repeatedAttemptsHighThreshold, "" + repeatedAttemptsHighWeight,
                "" + customerWindowDays, "" + customerFrequencyThreshold, "" + customerFrequencyWeight,
                "" + customerFrequencyHighThreshold, "" + customerFrequencyHighWeight, str(customerWindowTotal), "" + customerWindowTotalWeight,
                "" + mediumMin, "" + highMin, "" + criticalMin, "" + cumulativeExceedsOrderWeight, "" + orderCurrencyMismatchWeight,
                "" + settlementOverCreditWeight, "" + settlementShortfallWeight, "" + duplicateSettlementWeight);
        return sha256(s).substring(0, 16);
    }

    static String str(BigDecimal v) { return v == null ? "null" : v.stripTrailingZeros().toPlainString(); }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ── getters / setters (JavaBean binding) ──
    public BigDecimal getHighAmount() { return highAmount; }
    public void setHighAmount(BigDecimal v) { this.highAmount = v; }
    public int getHighAmountWeight() { return highAmountWeight; }
    public void setHighAmountWeight(int v) { this.highAmountWeight = v; }
    public BigDecimal getVeryHighAmount() { return veryHighAmount; }
    public void setVeryHighAmount(BigDecimal v) { this.veryHighAmount = v; }
    public int getVeryHighAmountWeight() { return veryHighAmountWeight; }
    public void setVeryHighAmountWeight(int v) { this.veryHighAmountWeight = v; }
    public int getExceedsOrderWeight() { return exceedsOrderWeight; }
    public void setExceedsOrderWeight(int v) { this.exceedsOrderWeight = v; }
    public int getMultipleRefundsForOrderWeight() { return multipleRefundsForOrderWeight; }
    public void setMultipleRefundsForOrderWeight(int v) { this.multipleRefundsForOrderWeight = v; }
    public int getOrderNotFoundWeight() { return orderNotFoundWeight; }
    public void setOrderNotFoundWeight(int v) { this.orderNotFoundWeight = v; }
    public int getOpenDiscrepancyWeight() { return openDiscrepancyWeight; }
    public void setOpenDiscrepancyWeight(int v) { this.openDiscrepancyWeight = v; }
    public int getRepeatedAttemptsThreshold() { return repeatedAttemptsThreshold; }
    public void setRepeatedAttemptsThreshold(int v) { this.repeatedAttemptsThreshold = v; }
    public int getRepeatedAttemptsWeight() { return repeatedAttemptsWeight; }
    public void setRepeatedAttemptsWeight(int v) { this.repeatedAttemptsWeight = v; }
    public int getRepeatedAttemptsHighThreshold() { return repeatedAttemptsHighThreshold; }
    public void setRepeatedAttemptsHighThreshold(int v) { this.repeatedAttemptsHighThreshold = v; }
    public int getRepeatedAttemptsHighWeight() { return repeatedAttemptsHighWeight; }
    public void setRepeatedAttemptsHighWeight(int v) { this.repeatedAttemptsHighWeight = v; }
    public int getCustomerWindowDays() { return customerWindowDays; }
    public void setCustomerWindowDays(int v) { this.customerWindowDays = v; }
    public int getCustomerFrequencyThreshold() { return customerFrequencyThreshold; }
    public void setCustomerFrequencyThreshold(int v) { this.customerFrequencyThreshold = v; }
    public int getCustomerFrequencyWeight() { return customerFrequencyWeight; }
    public void setCustomerFrequencyWeight(int v) { this.customerFrequencyWeight = v; }
    public int getCustomerFrequencyHighThreshold() { return customerFrequencyHighThreshold; }
    public void setCustomerFrequencyHighThreshold(int v) { this.customerFrequencyHighThreshold = v; }
    public int getCustomerFrequencyHighWeight() { return customerFrequencyHighWeight; }
    public void setCustomerFrequencyHighWeight(int v) { this.customerFrequencyHighWeight = v; }
    public BigDecimal getCustomerWindowTotal() { return customerWindowTotal; }
    public void setCustomerWindowTotal(BigDecimal v) { this.customerWindowTotal = v; }
    public int getCustomerWindowTotalWeight() { return customerWindowTotalWeight; }
    public void setCustomerWindowTotalWeight(int v) { this.customerWindowTotalWeight = v; }
    public int getMediumMin() { return mediumMin; }
    public void setMediumMin(int v) { this.mediumMin = v; }
    public int getHighMin() { return highMin; }
    public void setHighMin(int v) { this.highMin = v; }
    public int getCriticalMin() { return criticalMin; }
    public void setCriticalMin(int v) { this.criticalMin = v; }
    public boolean isHoldEnabled() { return holdEnabled; }
    public void setHoldEnabled(boolean v) { this.holdEnabled = v; }
    public int getHoldMinScore() { return holdMinScore; }
    public void setHoldMinScore(int v) { this.holdMinScore = v; }
    public int getMaxAgeMinutes() { return maxAgeMinutes; }
    public void setMaxAgeMinutes(int v) { this.maxAgeMinutes = v; }
    public int getCumulativeExceedsOrderWeight() { return cumulativeExceedsOrderWeight; }
    public void setCumulativeExceedsOrderWeight(int v) { this.cumulativeExceedsOrderWeight = v; }
    public int getOrderCurrencyMismatchWeight() { return orderCurrencyMismatchWeight; }
    public void setOrderCurrencyMismatchWeight(int v) { this.orderCurrencyMismatchWeight = v; }
    public int getSettlementOverCreditWeight() { return settlementOverCreditWeight; }
    public void setSettlementOverCreditWeight(int v) { this.settlementOverCreditWeight = v; }
    public int getSettlementShortfallWeight() { return settlementShortfallWeight; }
    public void setSettlementShortfallWeight(int v) { this.settlementShortfallWeight = v; }
    public int getDuplicateSettlementWeight() { return duplicateSettlementWeight; }
    public void setDuplicateSettlementWeight(int v) { this.duplicateSettlementWeight = v; }
    public boolean isHoldOnPolicyViolation() { return holdOnPolicyViolation; }
    public void setHoldOnPolicyViolation(boolean v) { this.holdOnPolicyViolation = v; }
    public int getReassessDedupeSeconds() { return reassessDedupeSeconds; }
    public void setReassessDedupeSeconds(int v) { this.reassessDedupeSeconds = v; }
}
