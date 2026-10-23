package com.refundguard.integration;

import com.refundguard.model.*;
import com.refundguard.repository.*;
import com.refundguard.service.IngestionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** End-to-end: ingestion -> risk -> hold -> decision, against H2. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HoldAndReleaseWorkflowTest {

    private static final String REVIEWER_PW = "Rg-Test-Pass-R-1234";
    private static final String OPERATOR_PW = "Rg-Test-Pass-O-1234";

    @Autowired MockMvc mvc;
    @Autowired IngestionService ingestion;
    @Autowired OrderRepository orders;
    @Autowired RefundRepository refunds;
    @Autowired RefundReviewRepository reviews;
    @Autowired RefundEventRepository events;
    @Autowired CaseRepository cases;
    @Autowired RiskAssessmentRepository assessments;

    @BeforeEach
    void clean() {
        cases.deleteAll(); reviews.deleteAll(); assessments.deleteAll(); events.deleteAll(); refunds.deleteAll(); orders.deleteAll();
    }

    private Refund createRefund(String orderId, String amount) {
        Order o = new Order();
        o.setExternalOrderId(orderId); o.setCustomerName("Test"); o.setCustomerEmail(orderId.toLowerCase() + "@example.com");
        o.setAmount(new BigDecimal(amount)); o.setCurrency("INR"); o.setStatus("PAID"); o.setPaymentMethod("UPI");
        o.setCreatedAt(LocalDateTime.now());
        orders.save(o);
        ingestion.ingestEvent("RAZORPAY", "evt-" + orderId, "refund.created", orderId, "pay_" + orderId, "rfnd_" + orderId,
                new BigDecimal(amount), "INR", "UPI", "{}");
        return refunds.findByOrderId(orderId).orElseThrow();
    }

    private static String reason(String r) { return "{\"reason\":\"" + r + "\"}"; }

    @Test
    void lowRiskRefundIsNotHeld() {
        Refund r = createRefund("ORD-LOW", "1500");
        assertNotEquals(RefundStatus.ON_HOLD, r.getDerivedStatus());
        assertFalse(reviews.existsByRefundId(r.getId()));
        assertEquals(1, assessments.findByRefundIdIn(List.of(r.getId())).size());
    }

    @Test
    void highRiskRefundIsHeldWithReviewCaseAndAudit() {
        Refund r = createRefund("ORD-HIGH", "150000"); // very-high amount (35) + customer total (15) = 50 -> hold
        assertEquals(RefundStatus.ON_HOLD, r.getDerivedStatus());
        RefundReview rv = reviews.findFirstByRefundIdAndStatus(r.getId(), com.refundguard.review.ReviewStatus.PENDING).orElseThrow();
        assertNotNull(rv.getHoldAssessmentId());
        assertTrue(cases.findByRefundIdAndStatus(r.getId(), "OPEN").stream().anyMatch(c -> "HIGH_RISK_REVIEW".equals(c.getIssueType())));
        assertTrue(events.findByRefundIdOrderByEventTimeAsc(r.getId()).stream().anyMatch(e -> "review.held".equals(e.getEventType())));
    }

    @Test
    void laterGatewayEventsDoNotOverwriteHold() {
        Refund r = createRefund("ORD-H2", "150000");
        ingestion.ingestEvent("RAZORPAY", "evt-proc-H2", "refund.processed", "ORD-H2", "pay_ORD-H2", "rfnd_ORD-H2",
                new BigDecimal("150000"), "INR", "UPI", "{}");
        assertEquals(RefundStatus.ON_HOLD, refunds.findById(r.getId()).orElseThrow().getDerivedStatus());
    }

    @Test
    void reviewerApprovesWithReasonAndReleaseIsSimulated() throws Exception {
        Refund r = createRefund("ORD-APP", "150000");
        mvcApprove(r.getId(), "Customer verified by phone", REVIEWER_PW, "reviewer").andExpect(status().isOk());

        Refund after = refunds.findById(r.getId()).orElseThrow();
        assertNotEquals(RefundStatus.ON_HOLD, after.getDerivedStatus());
        RefundReview rv = reviews.findByRefundIdOrderByHeldAtDesc(r.getId()).get(0);
        assertEquals("reviewer", rv.getDecidedBy());
        assertEquals("REVIEWER", rv.getDecidedByRole());
        assertEquals("Customer verified by phone", rv.getDecisionReason());
        assertNotNull(rv.getDecidedAt());
        assertNotNull(rv.getDecisionAssessmentId());
        assertTrue(rv.getSimulatedRelease());
        assertTrue(events.findByRefundIdOrderByEventTimeAsc(r.getId()).stream().anyMatch(e -> "review.released.simulated".equals(e.getEventType())));
        assertTrue(cases.findByRefundIdAndStatus(r.getId(), "OPEN").stream().noneMatch(c -> "HIGH_RISK_REVIEW".equals(c.getIssueType())));
    }

    @Test
    void rejectionIsTerminalAndKeepsCaseConsistent() throws Exception {
        Refund r = createRefund("ORD-REJ", "150000");
        mvc.perform(post("/api/refunds/" + r.getId() + "/review/reject").with(httpBasic("reviewer", REVIEWER_PW))
                .contentType(MediaType.APPLICATION_JSON).content(reason("Suspected fraud pattern"))).andExpect(status().isOk());
        assertEquals(RefundStatus.REJECTED, refunds.findById(r.getId()).orElseThrow().getDerivedStatus());
        assertTrue(cases.findByRefundIdAndStatus(r.getId(), "OPEN").isEmpty());
        // later gateway event must not resurrect it
        ingestion.ingestEvent("RAZORPAY", "evt-proc-REJ", "refund.processed", "ORD-REJ", "pay_ORD-REJ", "rfnd_ORD-REJ",
                new BigDecimal("150000"), "INR", "UPI", "{}");
        assertEquals(RefundStatus.REJECTED, refunds.findById(r.getId()).orElseThrow().getDerivedStatus());
    }

    @Test
    void secondOrConflictingDecisionIsRejectedWith409() throws Exception {
        Refund r = createRefund("ORD-TWICE", "150000");
        mvcApprove(r.getId(), "First decision recorded", REVIEWER_PW, "reviewer").andExpect(status().isOk());
        mvcApprove(r.getId(), "Second decision attempt", REVIEWER_PW, "reviewer").andExpect(status().isConflict());
        mvc.perform(post("/api/refunds/" + r.getId() + "/review/reject").with(httpBasic("reviewer", REVIEWER_PW))
                .contentType(MediaType.APPLICATION_JSON).content(reason("Conflicting reject attempt"))).andExpect(status().isConflict());
        assertEquals(1, reviews.findByRefundIdOrderByHeldAtDesc(r.getId()).size());
    }

    @Test
    void decisionRequiresReason() throws Exception {
        Refund r = createRefund("ORD-NOREASON", "150000");
        mvc.perform(post("/api/refunds/" + r.getId() + "/review/approve").with(httpBasic("reviewer", REVIEWER_PW))
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/refunds/" + r.getId() + "/review/reject").with(httpBasic("reviewer", REVIEWER_PW))
                .contentType(MediaType.APPLICATION_JSON).content(reason("   "))).andExpect(status().isBadRequest());
        assertEquals(RefundStatus.ON_HOLD, refunds.findById(r.getId()).orElseThrow().getDerivedStatus());
    }

    @Test
    void operatorCannotDecideAndRefundStaysOnHold() throws Exception {
        Refund r = createRefund("ORD-OPR", "150000");
        mvcApprove(r.getId(), "Operator trying to approve", OPERATOR_PW, "operator").andExpect(status().isForbidden());
        assertEquals(RefundStatus.ON_HOLD, refunds.findById(r.getId()).orElseThrow().getDerivedStatus());
    }

    @Test
    void clientSuppliedIdentityIsIgnored() throws Exception {
        Refund r = createRefund("ORD-SPOOF", "150000");
        mvc.perform(post("/api/refunds/" + r.getId() + "/review/approve").with(httpBasic("reviewer", REVIEWER_PW))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"Verified with the customer\",\"decidedBy\":\"admin\",\"role\":\"ADMIN\",\"riskScore\":0}"))
                .andExpect(status().isOk());
        assertEquals("reviewer", reviews.findByRefundIdOrderByHeldAtDesc(r.getId()).get(0).getDecidedBy());
    }

    @Test
    void decidingARefundThatIsNotOnHoldIs409() throws Exception {
        Refund r = createRefund("ORD-NOHOLD", "1500");
        mvcApprove(r.getId(), "Nothing to approve here", REVIEWER_PW, "reviewer").andExpect(status().isConflict());
    }

    @Test
    void genericCaseResolveCannotBypassReview() throws Exception {
        Refund r = createRefund("ORD-BYPASS", "150000");
        Case c = cases.findByRefundIdAndStatus(r.getId(), "OPEN").stream()
                .filter(x -> "HIGH_RISK_REVIEW".equals(x.getIssueType())).findFirst().orElseThrow();
        mvc.perform(post("/api/cases/" + c.getId() + "/resolve").with(httpBasic("operator", OPERATOR_PW))
                .contentType(MediaType.APPLICATION_JSON).content("{\"notes\":\"closing it myself\"}")).andExpect(status().isConflict());
        assertEquals(RefundStatus.ON_HOLD, refunds.findById(r.getId()).orElseThrow().getDerivedStatus());
    }

    private org.springframework.test.web.servlet.ResultActions mvcApprove(Long id, String reason, String pw, String user) throws Exception {
        return mvc.perform(post("/api/refunds/" + id + "/review/approve").with(httpBasic(user, pw))
                .contentType(MediaType.APPLICATION_JSON).content(reason(reason)));
    }
}
