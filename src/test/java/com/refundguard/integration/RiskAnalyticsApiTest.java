package com.refundguard.integration;

import com.refundguard.model.*;
import com.refundguard.repository.*;
import com.refundguard.review.ReviewStateMachine;
import com.refundguard.review.ReviewStatus;
import com.refundguard.risk.RiskScoringEngine;
import com.refundguard.risk.RiskScoringService;
import com.refundguard.service.IngestionService;
import com.refundguard.service.ReconciliationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Admin risk analytics + explainable scoring, end to end against H2 with real bootstrapped users. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RiskAnalyticsApiTest {

    private static final String ADMIN_PW = "Rg-Test-Pass-A-1234";
    private static final String OPERATOR_PW = "Rg-Test-Pass-O-1234";
    private static final String REVIEWER_PW = "Rg-Test-Pass-R-1234";

    @Autowired MockMvc mvc;
    @Autowired IngestionService ingestion;
    @Autowired ReconciliationService reconciliation;
    @Autowired RiskScoringService riskService;
    @Autowired OrderRepository orders;
    @Autowired RefundRepository refunds;
    @Autowired RefundReviewRepository reviews;
    @Autowired RefundEventRepository events;
    @Autowired CaseRepository cases;
    @Autowired RiskAssessmentRepository assessments;
    @Autowired RiskFactorRepository factors;
    @Autowired SettlementRowRepository settlements;

    @BeforeEach
    void clean() {
        cases.deleteAll(); reviews.deleteAll(); factors.deleteAllInBatch(); assessments.deleteAll(); settlements.deleteAll();
        events.deleteAll(); refunds.deleteAll(); orders.deleteAll();
    }

    /** Order of {@code orderAmount}, refund of {@code refundAmount} ingested through the real webhook path. */
    private Refund refund(String orderId, String orderAmount, String refundAmount) {
        Order o = new Order();
        o.setExternalOrderId(orderId); o.setCustomerName("Test"); o.setCustomerEmail(orderId.toLowerCase() + "@example.com");
        o.setAmount(new BigDecimal(orderAmount)); o.setCurrency("INR"); o.setStatus("PAID"); o.setPaymentMethod("UPI");
        o.setCreatedAt(LocalDateTime.now());
        orders.save(o);
        ingestion.ingestEvent("RAZORPAY", "evt-" + orderId, "refund.created", orderId, "pay_" + orderId, "rfnd_" + orderId,
                new BigDecimal(refundAmount), "INR", "UPI", "{}");
        return refunds.findByOrderId(orderId).orElseThrow();
    }

    private RiskAssessment latest(Refund r) { return assessments.findFirstByRefundIdOrderByAssessedAtDescIdDesc(r.getId()).orElseThrow(); }

    private long eventsOf(Refund r, String type) {
        return events.findByRefundIdOrderByEventTimeAsc(r.getId()).stream().filter(e -> type.equals(e.getEventType())).count();
    }

    // ── authorization ──

    @Test
    void analyticsAreAdminOnlyOnTheServer() throws Exception {
        for (String url : List.of("/api/admin/risk/summary", "/api/admin/risk/refunds", "/api/admin/risk/refunds/1")) {
            mvc.perform(get(url)).andExpect(status().isUnauthorized());
            mvc.perform(get(url).with(httpBasic("operator", OPERATOR_PW))).andExpect(status().isForbidden());
            mvc.perform(get(url).with(httpBasic("reviewer", REVIEWER_PW))).andExpect(status().isForbidden());
        }
        mvc.perform(post("/api/admin/risk/assess-unassessed").with(httpBasic("operator", OPERATOR_PW))).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/risk/summary").with(httpBasic("admin", ADMIN_PW))).andExpect(status().isOk());
        mvc.perform(get("/api/auth/me").with(httpBasic("admin", ADMIN_PW))).andExpect(jsonPath("$.permissions", hasItem("RISK_ANALYTICS_VIEW")));
        mvc.perform(get("/api/auth/me").with(httpBasic("operator", OPERATOR_PW))).andExpect(jsonPath("$.permissions", not(hasItem("RISK_ANALYTICS_VIEW"))));
    }

    // ── persistence + explainability ──

    @Test
    void assessmentPersistsOneFactorRowPerRuleWithEvidence() {
        Refund r = refund("ORD-X1", "100000", "150000"); // very high (35) + exceeds order (40) + customer total (15) = 90
        RiskAssessment a = latest(r);
        assertEquals(RiskScoringEngine.ENGINE_VERSION, a.getEngineVersion());
        assertEquals(90, a.getScore());
        assertEquals("CRITICAL", a.getLevel().name());
        assertTrue(a.isPolicyViolation());
        assertNull(a.getIndicatorsJson()); // factors live in rg_risk_factors now
        List<RiskFactor> rows = factors.findByAssessmentIds(List.of(a.getId()));
        assertEquals(List.of("VERY_HIGH_AMOUNT", "EXCEEDS_ORDER_AMOUNT", "CUSTOMER_REFUND_TOTAL"), rows.stream().map(RiskFactor::getRuleCode).toList());
        assertEquals(90, rows.stream().mapToInt(RiskFactor::getPoints).sum());
        RiskFactor exceeds = rows.get(1);
        assertTrue(exceeds.isPolicyViolation());
        assertTrue(exceeds.getEvidenceJson().contains("\"orderAmount\":\"₹100000.00\""));
    }

    @Test
    void policyViolationBelowScoreThresholdIsStillHeldForReview() {
        Refund r = refund("ORD-PV", "1000", "1500"); // exceeds order only: 40 points (< hold score 50)
        assertEquals(40, latest(r).getScore());
        assertEquals(RefundStatus.ON_HOLD, r.getDerivedStatus());
        RefundReview rv = reviews.findFirstByRefundIdAndStatusIn(r.getId(), ReviewStateMachine.openStatuses()).orElseThrow();
        assertEquals(ReviewStatus.PENDING, rv.getStatus());
        assertTrue(rv.getHoldReason().contains("policy violation"));
    }

    @Test
    void processingStatusAloneNeverChangesTheScore() {
        Refund r = refund("ORD-ST", "1000", "1500"); // non-zero score (exceeds order)
        int base = riskService.score(r, LocalDateTime.now()).score();
        assertEquals(40, base);
        for (RefundStatus s : List.of(RefundStatus.REQUESTED, RefundStatus.GATEWAY_PENDING, RefundStatus.GATEWAY_PROCESSED,
                RefundStatus.SETTLEMENT_MATCHED, RefundStatus.POTENTIAL_DELAY, RefundStatus.AMOUNT_MISMATCH, RefundStatus.FAILED_REPORTED)) {
            r.setDerivedStatus(s);
            assertEquals(base, riskService.score(r, LocalDateTime.now()).score(), s.name());
        }
    }

    @Test
    void detailExplainsScoreFactorsEvidenceMissingDataAndNextAction() throws Exception {
        Refund r = refund("ORD-DET", "100000", "150000");
        mvc.perform(get("/api/admin/risk/refunds/" + r.getId()).with(httpBasic("admin", ADMIN_PW)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assessed").value(true))
                .andExpect(jsonPath("$.assessment.riskScore").value(90))
                .andExpect(jsonPath("$.assessment.riskLevel").value("CRITICAL"))
                .andExpect(jsonPath("$.assessment.ruleVersion").value(RiskScoringEngine.ENGINE_VERSION))
                .andExpect(jsonPath("$.assessment.riskDescription", containsString("90/100 (CRITICAL)")))
                .andExpect(jsonPath("$.assessment.riskFactors[*].code", hasItem("EXCEEDS_ORDER_AMOUNT")))
                .andExpect(jsonPath("$.assessment.riskFactors[1].evidence.excess").value("₹50000.00"))
                .andExpect(jsonPath("$.assessment.riskFactors[1].policyViolation").value(true))
                .andExpect(jsonPath("$.assessment.recommendedAction", startsWith("Policy violation")))
                .andExpect(jsonPath("$.assessment.disclaimer", containsString("not proof of fraud")))
                .andExpect(jsonPath("$.openReview.status").value("PENDING"))
                .andExpect(jsonPath("$.history", hasSize(greaterThanOrEqualTo(1))));
        mvc.perform(get("/api/admin/risk/refunds/999999").with(httpBasic("admin", ADMIN_PW))).andExpect(status().isNotFound());
    }

    // ── summary + list ──

    @Test
    void summaryAndListAreComputedFromPersistedData() throws Exception {
        Refund low = refund("ORD-LOW", "5000", "1500");      // 0, LOW
        Refund crit = refund("ORD-CRIT", "100000", "150000"); // 90, CRITICAL (held -> pending review)
        Refund mid = refund("ORD-MID", "1000", "1500");       // 40, MEDIUM (policy violation, held)

        mvc.perform(get("/api/admin/risk/summary").with(httpBasic("admin", ADMIN_PW)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRefunds").value(3))
                .andExpect(jsonPath("$.assessed").value(3))
                .andExpect(jsonPath("$.unassessed").value(0))
                .andExpect(jsonPath("$.distribution.LOW").value(1))
                .andExpect(jsonPath("$.distribution.MEDIUM").value(1))
                .andExpect(jsonPath("$.distribution.CRITICAL").value(1))
                .andExpect(jsonPath("$.highOrCritical").value(1))
                .andExpect(jsonPath("$.policyViolations").value(2))
                .andExpect(jsonPath("$.pendingReviews").value(2))
                .andExpect(jsonPath("$.amountMismatches").value(0))
                .andExpect(jsonPath("$.bands.MEDIUM").value("30-59"))
                .andExpect(jsonPath("$.bands.CRITICAL").value("80-100"));

        mvc.perform(get("/api/admin/risk/refunds").with(httpBasic("admin", ADMIN_PW)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.content[0].refundId").value(crit.getId().intValue())) // highest score first
                .andExpect(jsonPath("$.content[0].explanation", containsString("exceeds the original order amount")));
        mvc.perform(get("/api/admin/risk/refunds?level=CRITICAL").with(httpBasic("admin", ADMIN_PW)))
                .andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get("/api/admin/risk/refunds?policyViolation=true").with(httpBasic("admin", ADMIN_PW)))
                .andExpect(jsonPath("$.totalElements").value(2));
        mvc.perform(get("/api/admin/risk/refunds?status=ON_HOLD").with(httpBasic("admin", ADMIN_PW)))
                .andExpect(jsonPath("$.totalElements").value(2));
        mvc.perform(get("/api/admin/risk/refunds?q=ord-low").with(httpBasic("admin", ADMIN_PW)))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].refundId").value(low.getId().intValue()));
        mvc.perform(get("/api/admin/risk/refunds?q=" + mid.getId()).with(httpBasic("admin", ADMIN_PW)))
                .andExpect(jsonPath("$.content[*].refundId", hasItem(mid.getId().intValue())));
        String today = LocalDate.now().toString();
        mvc.perform(get("/api/admin/risk/refunds?from=" + today + "&to=" + today).with(httpBasic("admin", ADMIN_PW)))
                .andExpect(jsonPath("$.totalElements").value(3));
        mvc.perform(get("/api/admin/risk/refunds?from=2000-01-01&to=2000-01-02").with(httpBasic("admin", ADMIN_PW)))
                .andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/api/admin/risk/refunds?size=1&page=1").with(httpBasic("admin", ADMIN_PW)))
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.totalPages").value(3));
        // bad input -> 400
        mvc.perform(get("/api/admin/risk/refunds?level=EXTREME").with(httpBasic("admin", ADMIN_PW))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/admin/risk/refunds?size=1000").with(httpBasic("admin", ADMIN_PW))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/admin/risk/refunds?from=yesterday").with(httpBasic("admin", ADMIN_PW))).andExpect(status().isBadRequest());
    }

    @Test
    void unassessedRefundsAreListedAndCanBeBackfilledWithoutHolding() throws Exception {
        Refund r = refund("ORD-BF", "5000", "1500");
        RefundStatus before = refunds.findById(r.getId()).orElseThrow().getDerivedStatus();
        factors.deleteAllInBatch();
        assessments.deleteAll();
        mvc.perform(get("/api/admin/risk/refunds?level=UNASSESSED").with(httpBasic("admin", ADMIN_PW)))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].assessed").value(false));
        mvc.perform(post("/api/admin/risk/assess-unassessed").with(httpBasic("admin", ADMIN_PW)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.assessed").value(1));
        assertEquals("BACKFILL", latest(r).getTrigger());
        assertEquals(before, refunds.findById(r.getId()).orElseThrow().getDerivedStatus());
        assertEquals(1, eventsOf(r, "risk.assessed"));
        mvc.perform(post("/api/admin/risk/assess-unassessed").with(httpBasic("admin", ADMIN_PW)))
                .andExpect(jsonPath("$.assessed").value(0));
    }

    // ── duplicates, audit, recalculation ──

    @Test
    void repeatedReassessmentDoesNotDuplicateAndRealChangesAreAudited() throws Exception {
        Refund r = refund("ORD-RE", "5000", "1500");
        long rows = assessments.count();
        // inputs unchanged since ingestion a moment ago -> reused, nothing stored, nothing audited
        mvc.perform(post("/api/refunds/" + r.getId() + "/risk/reassess").with(httpBasic("operator", OPERATOR_PW)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.reused").value(true));
        assertEquals(rows, assessments.count());
        assertEquals(0, eventsOf(r, "risk.reassessed"));

        // a second refund record for the same order changes the inputs -> new assessment + audit entry
        Refund twin = new Refund();
        twin.setOrderId("ORD-RE"); twin.setAmount(new BigDecimal("100")); twin.setCurrency("INR");
        twin.setDerivedStatus(RefundStatus.REQUESTED); twin.setRequestedAt(LocalDateTime.now());
        refunds.save(twin);
        mvc.perform(post("/api/refunds/" + r.getId() + "/risk/reassess").with(httpBasic("operator", OPERATOR_PW)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reused").value(false))
                .andExpect(jsonPath("$.riskFactors[*].code", hasItem("MULTIPLE_REFUNDS_FOR_ORDER")));
        assertEquals(rows + 1, assessments.count());
        assertEquals(1, eventsOf(r, "risk.reassessed"));
        mvc.perform(post("/api/refunds/" + r.getId() + "/risk/reassess").with(httpBasic("operator", OPERATOR_PW)))
                .andExpect(jsonPath("$.reused").value(true));
        assertEquals(1, eventsOf(r, "risk.reassessed"));
        // reviewers may reassess too (existing RISK_ASSESS permission); unauthenticated may not
        mvc.perform(post("/api/refunds/" + r.getId() + "/risk/reassess")).andExpect(status().isUnauthorized());
    }

    @Test
    void settlementMismatchTriggersReassessmentWithoutDoubleCountingTheCase() throws Exception {
        Refund r = refund("ORD-SET", "5000", "1500");
        String csv = "refund_reference,utr_number,amount,settlement_date\nrfnd_ORD-SET,UTR123,1400," + LocalDate.now() + "\n";
        reconciliation.uploadSettlementCsv(new MockMultipartFile("file", "s.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)));

        Refund after = refunds.findById(r.getId()).orElseThrow();
        assertEquals(RefundStatus.AMOUNT_MISMATCH, after.getDerivedStatus());
        RiskAssessment a = latest(r);
        assertEquals(RiskScoringService.TRIGGER_SETTLEMENT, a.getTrigger());
        List<String> codes = factors.findByAssessmentIds(List.of(a.getId())).stream().map(RiskFactor::getRuleCode).toList();
        assertTrue(codes.contains("SETTLEMENT_AMOUNT_MISMATCH"));
        assertFalse(codes.contains("OPEN_DISCREPANCY"), "the open AMOUNT_MISMATCH case describes the same fact");
        assertEquals(20, a.getScore()); // shortfall weight
        assertNotEquals(RefundStatus.ON_HOLD, after.getDerivedStatus()); // scoring never holds/releases a settled refund

        mvc.perform(get("/api/admin/risk/summary").with(httpBasic("admin", ADMIN_PW)))
                .andExpect(jsonPath("$.amountMismatches").value(1));
        mvc.perform(get("/api/admin/risk/refunds?mismatch=true").with(httpBasic("admin", ADMIN_PW)))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].amountMismatch").value(true));
    }
}
