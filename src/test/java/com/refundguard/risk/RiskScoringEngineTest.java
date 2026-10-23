package com.refundguard.risk;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RiskScoringEngineTest {

    private static final LocalDateTime T = LocalDateTime.of(2026, 10, 20, 12, 0);

    private final RiskConfig cfg = new RiskConfig();
    private final RiskScoringEngine engine = new RiskScoringEngine(cfg);

    /** A refund with a clean, fully-known history: should score 0. */
    private static RiskInput clean(String amount) {
        return new RiskInput(1L, new BigDecimal(amount), "INR", true, new BigDecimal("100000"), "INR",
                1, 1, 1, new BigDecimal(amount), 0);
    }

    private static boolean has(RiskResult r, String code) {
        return r.indicators().stream().anyMatch(i -> i.code().equals(code));
    }

    @Test
    void cleanRefundScoresZeroAndLow() {
        RiskResult r = engine.assess(clean("1999"), T);
        assertEquals(0, r.score());
        assertEquals(RiskLevel.LOW, r.level());
        assertTrue(r.indicators().isEmpty());
        assertTrue(r.notEvaluated().isEmpty());
        assertEquals(T, r.assessedAt());
        assertEquals("RULE_BASED_V2", r.engineVersion());
    }

    // ── amount boundaries ──
    @Test
    void amountJustBelowHighThresholdDoesNotTrigger() {
        assertFalse(has(engine.assess(clean("24999.99"), T), RiskScoringEngine.HIGH_AMOUNT));
    }

    @Test
    void amountExactlyAtHighThresholdTriggersHighTier() {
        RiskResult r = engine.assess(clean("25000"), T);
        assertTrue(has(r, RiskScoringEngine.HIGH_AMOUNT));
        assertFalse(has(r, RiskScoringEngine.VERY_HIGH_AMOUNT));
        assertEquals(20, r.score());
    }

    @Test
    void amountAtVeryHighThresholdReplacesHighTierNotAddsToIt() {
        // customer total kept small so only the amount tier is exercised
        RiskInput in = new RiskInput(1L, new BigDecimal("100000"), "INR", true, new BigDecimal("200000"), "INR", 1, 1, 1, new BigDecimal("1000"), 0);
        RiskResult r = engine.assess(in, T);
        assertTrue(has(r, RiskScoringEngine.VERY_HIGH_AMOUNT));
        assertFalse(has(r, RiskScoringEngine.HIGH_AMOUNT));
        assertEquals(35, r.score());
    }

    // ── order consistency ──
    @Test
    void refundAboveOrderAmountTriggers() {
        RiskInput in = new RiskInput(1L, new BigDecimal("5000.01"), "INR", true, new BigDecimal("5000"), "INR", 1, 1, 1, new BigDecimal("5000.01"), 0);
        RiskResult r = engine.assess(in, T);
        assertTrue(has(r, RiskScoringEngine.EXCEEDS_ORDER_AMOUNT));
        assertEquals(40, r.score());
    }

    @Test
    void refundEqualToOrderAmountDoesNotTrigger() {
        RiskInput in = new RiskInput(1L, new BigDecimal("5000"), "INR", true, new BigDecimal("5000"), "INR", 1, 1, 1, new BigDecimal("5000"), 0);
        assertFalse(has(engine.assess(in, T), RiskScoringEngine.EXCEEDS_ORDER_AMOUNT));
    }

    @Test
    void currencyMismatchSkipsOrderComparisonAndSaysSo() {
        RiskInput in = new RiskInput(1L, new BigDecimal("9000"), "USD", true, new BigDecimal("5000"), "INR", 1, 1, 1, new BigDecimal("9000"), 0);
        RiskResult r = engine.assess(in, T);
        assertFalse(has(r, RiskScoringEngine.EXCEEDS_ORDER_AMOUNT));
        assertTrue(r.notEvaluated().stream().anyMatch(s -> s.contains("currencies differ")));
    }

    // ── missing data ──
    @Test
    void missingOrderIsReportedAsUnavailableNotScoredByDefault() {
        RiskInput in = new RiskInput(1L, new BigDecimal("1500"), "INR", false, null, null, 1, 1, null, null, 0);
        RiskResult r = engine.assess(in, T);
        assertFalse(has(r, RiskScoringEngine.ORDER_NOT_FOUND)); // missing data is not risk (weight 0 by default)
        assertEquals(0, r.score());
        assertEquals(1, r.notEvaluated().size());
        assertTrue(r.notEvaluated().get(0).contains("Order record not found"));
    }

    @Test
    void missingCustomerEmailSkipsCustomerRules() {
        RiskInput in = new RiskInput(1L, new BigDecimal("1500"), "INR", true, new BigDecimal("2000"), "INR", 1, 1, null, null, 0);
        RiskResult r = engine.assess(in, T);
        assertEquals(0, r.score());
        assertTrue(r.notEvaluated().stream().anyMatch(s -> s.contains("customer e-mail")));
    }

    @Test
    void missingEventHistorySkipsRepeatedAttemptRule() {
        RiskInput in = new RiskInput(1L, new BigDecimal("1500"), "INR", true, new BigDecimal("2000"), "INR", 1, null, 1, new BigDecimal("1500"), 0);
        RiskResult r = engine.assess(in, T);
        assertEquals(0, r.score());
        assertTrue(r.notEvaluated().stream().anyMatch(s -> s.contains("Gateway event history")));
    }

    @Test
    void missingAmountIsReportedNotScored() {
        RiskInput in = new RiskInput(1L, null, "INR", true, new BigDecimal("2000"), "INR", 1, 1, 1, null, 0);
        RiskResult r = engine.assess(in, T);
        assertEquals(0, r.score());
        assertTrue(r.notEvaluated().stream().anyMatch(s -> s.contains("amount is missing")));
    }

    // ── repeated attempts / frequency boundaries ──
    @Test
    void repeatedAttemptsTiers() {
        assertFalse(has(engine.assess(withCreated(1), T), RiskScoringEngine.REPEATED_ATTEMPTS));
        assertEquals(15, engine.assess(withCreated(2), T).score());
        assertEquals(25, engine.assess(withCreated(3), T).score());
        assertEquals(25, engine.assess(withCreated(9), T).score());
    }

    private static RiskInput withCreated(int n) {
        return new RiskInput(1L, new BigDecimal("1000"), "INR", true, new BigDecimal("2000"), "INR", 1, n, 1, new BigDecimal("1000"), 0);
    }

    @Test
    void customerFrequencyTiersAndBoundaries() {
        assertEquals(0, engine.assess(withCustomerCount(2), T).score());
        assertEquals(20, engine.assess(withCustomerCount(3), T).score());
        assertEquals(20, engine.assess(withCustomerCount(4), T).score());
        assertEquals(30, engine.assess(withCustomerCount(5), T).score());
    }

    private static RiskInput withCustomerCount(int n) {
        return new RiskInput(1L, new BigDecimal("1000"), "INR", true, new BigDecimal("2000"), "INR", 1, 1, n, new BigDecimal("1000"), 0);
    }

    @Test
    void customerTotalTriggersAtThreshold() {
        RiskInput in = new RiskInput(1L, new BigDecimal("1000"), "INR", true, new BigDecimal("2000"), "INR", 1, 1, 2, new BigDecimal("100000"), 0);
        RiskResult r = engine.assess(in, T);
        assertTrue(has(r, RiskScoringEngine.CUSTOMER_REFUND_TOTAL));
        assertEquals(15, r.score());
    }

    @Test
    void multipleRefundsForOrderAndOpenCasesTrigger() {
        RiskInput in = new RiskInput(1L, new BigDecimal("1000"), "INR", true, new BigDecimal("2000"), "INR", 2, 1, 1, new BigDecimal("1000"), 1);
        RiskResult r = engine.assess(in, T);
        assertTrue(has(r, RiskScoringEngine.MULTIPLE_REFUNDS_FOR_ORDER));
        assertTrue(has(r, RiskScoringEngine.OPEN_DISCREPANCY));
        assertEquals(45, r.score());
    }

    // ── multiple simultaneous indicators, cap, categories ──
    @Test
    void multipleIndicatorsAddUpAndReasonsAreInRuleOrder() {
        // very high amount (35) + exceeds order (40) + customer freq high (30) + repeated high (25) = 130 -> capped
        RiskInput in = new RiskInput(7L, new BigDecimal("150000"), "INR", true, new BigDecimal("120000"), "INR", 1, 3, 5, new BigDecimal("150000"), 0);
        RiskResult r = engine.assess(in, T);
        assertEquals(100, r.score());
        assertEquals(RiskLevel.CRITICAL, r.level());
        List<String> codes = r.indicators().stream().map(RiskIndicator::code).toList();
        assertEquals(List.of(RiskScoringEngine.VERY_HIGH_AMOUNT, RiskScoringEngine.EXCEEDS_ORDER_AMOUNT,
                RiskScoringEngine.CUSTOMER_REFUND_FREQUENCY, RiskScoringEngine.CUSTOMER_REFUND_TOTAL,
                RiskScoringEngine.REPEATED_ATTEMPTS), codes);
        assertTrue(r.reasons().get(0).startsWith("+35 "));
    }

    @Test
    void twoIndicatorsBelowCapAddExactly() {
        // high amount (20) + repeated (15) = 35 -> MEDIUM
        RiskInput in = new RiskInput(1L, new BigDecimal("30000"), "INR", true, new BigDecimal("40000"), "INR", 1, 2, 1, new BigDecimal("30000"), 0);
        RiskResult r = engine.assess(in, T);
        assertEquals(35, r.score());
        assertEquals(RiskLevel.MEDIUM, r.level());
    }

    @Test
    void categoryBoundariesAreInclusiveLowerBounds() {
        assertEquals(RiskLevel.LOW, cfg.levelFor(0));
        assertEquals(RiskLevel.LOW, cfg.levelFor(29));
        assertEquals(RiskLevel.MEDIUM, cfg.levelFor(30));
        assertEquals(RiskLevel.MEDIUM, cfg.levelFor(59));
        assertEquals(RiskLevel.HIGH, cfg.levelFor(60));
        assertEquals(RiskLevel.HIGH, cfg.levelFor(79));
        assertEquals(RiskLevel.CRITICAL, cfg.levelFor(80));
        assertEquals(RiskLevel.CRITICAL, cfg.levelFor(100));
    }

    // ── determinism & configurability ──
    @Test
    void sameInputAndConfigGiveIdenticalResults() {
        RiskInput in = new RiskInput(7L, new BigDecimal("150000"), "INR", true, new BigDecimal("120000"), "INR", 1, 3, 5, new BigDecimal("150000"), 0);
        assertEquals(engine.assess(in, T), new RiskScoringEngine(new RiskConfig()).assess(in, T));
    }

    @Test
    void fingerprintChangesWhenInputOrConfigChanges() {
        RiskInput a = clean("1000");
        RiskInput b = clean("1001");
        assertNotEquals(engine.fingerprint(a), engine.fingerprint(b));
        RiskConfig other = new RiskConfig();
        other.setHighAmount(new BigDecimal("20000"));
        assertNotEquals(engine.fingerprint(a), new RiskScoringEngine(other).fingerprint(a));
        assertEquals(engine.fingerprint(a), engine.fingerprint(clean("1000")));
    }

    @Test
    void weightsAndThresholdsAreConfigurable() {
        RiskConfig c = new RiskConfig();
        c.setHighAmount(new BigDecimal("1000"));
        c.setVeryHighAmount(new BigDecimal("2000"));
        c.setHighAmountWeight(60);
        RiskResult r = new RiskScoringEngine(c).assess(clean("1500"), T);
        assertEquals(60, r.score());
        assertEquals(RiskLevel.HIGH, r.level());
        assertTrue(c.shouldHold(r.score()));
    }

    @Test
    void zeroWeightDisablesARule() {
        RiskConfig c = new RiskConfig();
        c.setHighAmountWeight(0);
        assertTrue(new RiskScoringEngine(c).assess(clean("30000"), T).indicators().isEmpty());
    }

    // ── hold criterion ──
    @Test
    void holdCriterionUsesConfiguredMinimumAndEnabledFlag() {
        assertFalse(cfg.shouldHold(49));
        assertTrue(cfg.shouldHold(50));
        RiskConfig off = new RiskConfig();
        off.setHoldEnabled(false);
        assertFalse(off.shouldHold(100));
    }

    // ── configuration validation ──
    @Test
    void defaultConfigIsValid() {
        new RiskConfig().validate();
    }

    @Test
    void invalidThresholdOrderIsRejected() {
        RiskConfig c = new RiskConfig();
        c.setHighMin(20); // below mediumMin (30)
        assertThrows(IllegalStateException.class, c::validate);
    }

    @Test
    void veryHighAmountMustExceedHighAmount() {
        RiskConfig c = new RiskConfig();
        c.setVeryHighAmount(new BigDecimal("10"));
        assertThrows(IllegalStateException.class, c::validate);
    }

    @Test
    void outOfRangeWeightIsRejected() {
        RiskConfig c = new RiskConfig();
        c.setExceedsOrderWeight(101);
        assertThrows(IllegalStateException.class, c::validate);
    }

    // ── V2: explainable factors, settlement rules, policy violations, no double counting ──

    /** Full V2 input: refund 1000 INR on a 2000 INR order, one refund, nothing settled. */
    private static RiskInput v2(BigDecimal amount, String currency, BigDecimal orderAmount, int refundsForOrder,
                                BigDecimal refundsTotal, BigDecimal settled, int utrs, int openCases, int openMismatch, int openDup) {
        return new RiskInput(1L, amount, currency, true, orderAmount, "INR", refundsForOrder, 1, 1, amount, openCases,
                refundsTotal, settled, utrs, openMismatch, openDup);
    }

    private static RiskIndicator get(RiskResult r, String code) {
        return r.indicators().stream().filter(i -> i.code().equals(code)).findFirst().orElseThrow();
    }

    @Test
    void configuredOrderNotFoundWeightStillApplies() {
        RiskConfig c = new RiskConfig();
        c.setOrderNotFoundWeight(10);
        RiskResult r = new RiskScoringEngine(c).assess(new RiskInput(1L, new BigDecimal("1500"), "INR", false, null, null, 1, 1, null, null, 0), T);
        assertTrue(has(r, RiskScoringEngine.ORDER_NOT_FOUND));
        assertEquals(10, r.score());
    }

    @Test
    void exceedingOrderIsAPolicyViolationWithEvidence() {
        RiskInput in = new RiskInput(1L, new BigDecimal("1500"), "INR", true, new BigDecimal("1000"), "INR", 1, 1, 1, new BigDecimal("1500"), 0);
        RiskResult r = engine.assess(in, T);
        RiskIndicator i = get(r, RiskScoringEngine.EXCEEDS_ORDER_AMOUNT);
        assertTrue(i.policyViolation());
        assertTrue(r.policyViolation());
        assertEquals("₹1500.00", i.evidence().get("refundAmount"));
        assertEquals("₹1000.00", i.evidence().get("orderAmount"));
        assertEquals("₹500.00", i.evidence().get("excess"));
        assertEquals(40, r.score());
        assertEquals(RiskLevel.MEDIUM, r.level());
        assertFalse(cfg.shouldHold(r.score()));
        assertTrue(cfg.wouldHold(r.score(), r.policyViolation())); // policy violation forces review
    }

    @Test
    void currencyMismatchWithOrderIsAPolicyViolation() {
        RiskInput in = new RiskInput(1L, new BigDecimal("900"), "USD", true, new BigDecimal("5000"), "INR", 1, 1, 1, new BigDecimal("900"), 0);
        RiskResult r = engine.assess(in, T);
        RiskIndicator i = get(r, RiskScoringEngine.ORDER_CURRENCY_MISMATCH);
        assertTrue(i.policyViolation());
        assertEquals("USD", i.evidence().get("refundCurrency"));
        assertEquals(30, r.score());
        RiskConfig off = new RiskConfig();
        off.setHoldOnPolicyViolation(false);
        assertFalse(off.wouldHold(r.score(), r.policyViolation()));
    }

    @Test
    void cumulativeRefundsAboveOrderReplaceMultipleRefundsRule() {
        // two refunds of 1000 on a 1500 order: neither exceeds alone, together they do
        RiskResult r = engine.assess(v2(new BigDecimal("1000"), "INR", new BigDecimal("1500"), 2, new BigDecimal("2000"), null, 0, 0, 0, 0), T);
        assertTrue(has(r, RiskScoringEngine.CUMULATIVE_EXCEEDS_ORDER));
        assertFalse(has(r, RiskScoringEngine.MULTIPLE_REFUNDS_FOR_ORDER)); // no double counting
        assertTrue(r.policyViolation());
        assertEquals(40, r.score());
        assertEquals("₹500.00", get(r, RiskScoringEngine.CUMULATIVE_EXCEEDS_ORDER).evidence().get("excess"));
    }

    @Test
    void multipleRefundsWithinOrderAmountAreNotAViolation() {
        RiskResult r = engine.assess(v2(new BigDecimal("500"), "INR", new BigDecimal("2000"), 2, new BigDecimal("1000"), null, 0, 0, 0, 0), T);
        assertTrue(has(r, RiskScoringEngine.MULTIPLE_REFUNDS_FOR_ORDER));
        assertFalse(has(r, RiskScoringEngine.CUMULATIVE_EXCEEDS_ORDER));
        assertFalse(r.policyViolation());
        assertEquals(30, r.score());
    }

    @Test
    void cumulativeRuleDoesNotStackOnSingleRefundExcess() {
        RiskResult r = engine.assess(v2(new BigDecimal("3000"), "INR", new BigDecimal("2000"), 2, new BigDecimal("4000"), null, 0, 0, 0, 0), T);
        assertTrue(has(r, RiskScoringEngine.EXCEEDS_ORDER_AMOUNT));
        assertFalse(has(r, RiskScoringEngine.CUMULATIVE_EXCEEDS_ORDER));
        assertTrue(has(r, RiskScoringEngine.MULTIPLE_REFUNDS_FOR_ORDER));
    }

    @Test
    void unknownRefundTotalIsReportedNotScored() {
        RiskResult r = engine.assess(v2(new BigDecimal("500"), "INR", new BigDecimal("2000"), 2, null, null, 0, 0, 0, 0), T);
        assertFalse(has(r, RiskScoringEngine.CUMULATIVE_EXCEEDS_ORDER));
        assertTrue(r.notEvaluated().stream().anyMatch(s -> s.contains("cumulative")));
    }

    @Test
    void settlementOverCreditWeighsMoreThanShortfall() {
        RiskResult over = engine.assess(v2(new BigDecimal("1000"), "INR", new BigDecimal("2000"), 1, new BigDecimal("1000"), new BigDecimal("1200"), 1, 0, 0, 0), T);
        RiskResult under = engine.assess(v2(new BigDecimal("1000"), "INR", new BigDecimal("2000"), 1, new BigDecimal("1000"), new BigDecimal("900"), 1, 0, 0, 0), T);
        assertEquals(45, over.score());
        assertEquals(20, under.score());
        assertEquals("₹200.00", get(over, RiskScoringEngine.SETTLEMENT_AMOUNT_MISMATCH).evidence().get("difference"));
        assertFalse(over.policyViolation());
    }

    @Test
    void notSettledYetIsNeitherRiskNorMissingData() {
        RiskResult r = engine.assess(v2(new BigDecimal("1000"), "INR", new BigDecimal("2000"), 1, new BigDecimal("1000"), null, 0, 0, 0, 0), T);
        assertEquals(0, r.score());
        assertTrue(r.notEvaluated().isEmpty());
    }

    @Test
    void openMismatchCaseIsNotCountedTwiceWithTheSettlementRule() {
        // settled short + an open AMOUNT_MISMATCH case describing the same fact
        RiskResult r = engine.assess(v2(new BigDecimal("1000"), "INR", new BigDecimal("2000"), 1, new BigDecimal("1000"), new BigDecimal("900"), 1, 1, 1, 0), T);
        assertTrue(has(r, RiskScoringEngine.SETTLEMENT_AMOUNT_MISMATCH));
        assertFalse(has(r, RiskScoringEngine.OPEN_DISCREPANCY));
        assertEquals(20, r.score());
        // an unrelated open case (e.g. REFUND_FAILED) still counts
        RiskResult r2 = engine.assess(v2(new BigDecimal("1000"), "INR", new BigDecimal("2000"), 1, new BigDecimal("1000"), new BigDecimal("900"), 1, 2, 1, 0), T);
        assertEquals(35, r2.score());
        assertEquals("1", get(r2, RiskScoringEngine.OPEN_DISCREPANCY).evidence().get("openDiscrepancyCases"));
    }

    @Test
    void duplicateSettlementRuleAndItsCaseAreNotDoubleCounted() {
        RiskResult r = engine.assess(v2(new BigDecimal("1000"), "INR", new BigDecimal("2000"), 1, new BigDecimal("1000"), new BigDecimal("1000"), 2, 1, 0, 1), T);
        assertTrue(has(r, RiskScoringEngine.DUPLICATE_SETTLEMENT));
        assertFalse(has(r, RiskScoringEngine.OPEN_DISCREPANCY));
        assertEquals(40, r.score());
    }

    @Test
    void scoreIsCappedAt100AndEveryFactorKeepsItsPoints() {
        RiskInput in = new RiskInput(7L, new BigDecimal("150000"), "INR", true, new BigDecimal("120000"), "INR", 2, 3, 5,
                new BigDecimal("150000"), 1, new BigDecimal("300000"), new BigDecimal("160000"), 2, 0, 0);
        RiskResult r = engine.assess(in, T);
        assertEquals(100, r.score());
        assertTrue(r.indicators().stream().mapToInt(RiskIndicator::points).sum() > 100);
        assertEquals(RiskLevel.CRITICAL, r.level());
    }

    @Test
    void fingerprintCoversSettlementInputs() {
        RiskInput a = v2(new BigDecimal("1000"), "INR", new BigDecimal("2000"), 1, new BigDecimal("1000"), null, 0, 0, 0, 0);
        RiskInput b = v2(new BigDecimal("1000"), "INR", new BigDecimal("2000"), 1, new BigDecimal("1000"), new BigDecimal("1000"), 1, 0, 0, 0);
        assertNotEquals(engine.fingerprint(a), engine.fingerprint(b));
    }

    @Test
    void explainerDescribesFactorsMissingDataAndCap() {
        List<java.util.Map<String, Object>> f = List.of(
                java.util.Map.of("code", "A", "points", 60, "reason", "Big"), java.util.Map.of("code", "B", "points", 50, "reason", "Small"));
        String d = RiskExplainer.description(100, RiskLevel.CRITICAL, f, List.of("x"));
        assertTrue(d.contains("100/100 (CRITICAL)"));
        assertTrue(d.contains("Big (+60)"));
        assertTrue(d.contains("capped at 100"));
        assertTrue(d.contains("not counted as risk"));
        assertTrue(RiskExplainer.description(0, RiskLevel.LOW, List.of(), List.of()).contains("No risk rule"));
        assertTrue(RiskExplainer.recommendedActions(RiskLevel.LOW, List.of(), true, "REQUESTED").get(0).startsWith("Policy violation"));
        assertEquals("No action required beyond normal processing.", RiskExplainer.recommendedActions(RiskLevel.LOW, List.of(), false, "REQUESTED").get(0));
        assertTrue(RiskExplainer.DISCLAIMER.contains("not proof of fraud"));
    }
}
