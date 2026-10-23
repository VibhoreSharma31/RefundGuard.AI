package com.refundguard.integration;

import com.refundguard.model.*;
import com.refundguard.repository.*;
import com.refundguard.review.RefundReviewService;
import com.refundguard.review.ReviewAction;
import com.refundguard.review.ReviewStateMachine;
import com.refundguard.review.ReviewStatus;
import com.refundguard.security.Actor;
import com.refundguard.security.Role;
import com.refundguard.service.IngestionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Reviewer RBAC + operator-to-reviewer case workflow, end to end against H2 with real (bootstrapped) users. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReviewerCaseWorkflowTest {

    private static final String ADMIN_PW = "Rg-Test-Pass-A-1234";
    private static final String OPERATOR_PW = "Rg-Test-Pass-O-1234";
    private static final String REVIEWER_PW = "Rg-Test-Pass-R-1234";
    private static final String REVIEWER2_PW = "Rg-Test-Pass-R2-1234";
    private static final String FLAG_REASON = "Customer disputes the amount, suspicious pattern";

    @Autowired MockMvc mvc;
    @Autowired IngestionService ingestion;
    @Autowired OrderRepository orders;
    @Autowired RefundRepository refunds;
    @Autowired RefundReviewRepository reviews;
    @Autowired RefundEventRepository events;
    @Autowired CaseRepository cases;
    @Autowired RiskAssessmentRepository assessments;
    @Autowired AppUserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired RefundReviewService reviewService;

    @BeforeEach
    void clean() {
        cases.deleteAll(); reviews.deleteAll(); assessments.deleteAll(); events.deleteAll(); refunds.deleteAll(); orders.deleteAll();
        if (!users.existsByUsernameIgnoreCase("reviewer2")) {
            AppUser u = new AppUser();
            u.setUsername("reviewer2");
            u.setPasswordHash(encoder.encode(REVIEWER2_PW));
            u.setRole(Role.REVIEWER);
            u.setEnabled(true);
            u.setCreatedAt(LocalDateTime.now());
            u.setUpdatedAt(LocalDateTime.now());
            users.save(u);
        }
    }

    // ── helpers ──

    /** Low-risk refund (not auto-held): ends in GATEWAY_PENDING after the refund.created event. */
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

    private ResultActions post(String url, String user, String pw, String json) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post(url).with(httpBasic(user, pw))
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions act(Long reviewId, String path, String user, String pw, String text) throws Exception {
        return post("/api/reviews/" + reviewId + "/" + path, user, pw, reason(text));
    }

    private RefundReview submitAs(Refund r, String user, String pw) throws Exception {
        post("/api/refunds/" + r.getId() + "/review/submit", user, pw, reason(FLAG_REASON)).andExpect(status().isOk());
        return reviews.findFirstByRefundIdAndStatusIn(r.getId(), ReviewStateMachine.openStatuses()).orElseThrow();
    }

    private RefundReview submit(Refund r) throws Exception { return submitAs(r, "operator", OPERATOR_PW); }

    private RefundReview review(Long id) { return reviews.findById(id).orElseThrow(); }

    private RefundStatus refundStatus(Refund r) { return refunds.findById(r.getId()).orElseThrow().getDerivedStatus(); }

    private boolean hasEvent(Refund r, String type) {
        return events.findByRefundIdOrderByEventTimeAsc(r.getId()).stream().anyMatch(e -> type.equals(e.getEventType()));
    }

    private long countEvents(Refund r, String type) {
        return events.findByRefundIdOrderByEventTimeAsc(r.getId()).stream().filter(e -> type.equals(e.getEventType())).count();
    }

    private boolean openReviewCase(Refund r) {
        return cases.findByRefundIdAndStatus(r.getId(), "OPEN").stream().anyMatch(c -> "HIGH_RISK_REVIEW".equals(c.getIssueType()));
    }

    // ── Reviewer login / authorization ──

    @Test
    void reviewerSignsInThroughExistingLoginWithReviewerPermissions() throws Exception {
        mvc.perform(get("/api/auth/me").with(httpBasic("reviewer", REVIEWER_PW)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("REVIEWER"))
                .andExpect(jsonPath("$.permissions", hasItem("REVIEW_DECIDE")))
                .andExpect(jsonPath("$.permissions", not(hasItem("REVIEW_SUBMIT"))))
                .andExpect(jsonPath("$.permissions", not(hasItem("REVIEW_ESCALATION_DECIDE"))));
        mvc.perform(get("/api/reviews/summary").with(httpBasic("reviewer", REVIEWER_PW))).andExpect(status().isOk());
        mvc.perform(get("/api/reviews/summary").with(httpBasic("reviewer", "wrong-password-123"))).andExpect(status().isUnauthorized());
    }

    @Test
    void reviewEndpointsRequireAuthentication() throws Exception {
        mvc.perform(get("/api/reviews/summary")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/reviews/1")).andExpect(status().isUnauthorized());
        mvc.perform(MockMvcRequestBuilders.post("/api/reviews/1/approve").contentType(MediaType.APPLICATION_JSON)
                .content(reason("no credentials given"))).andExpect(status().isUnauthorized());
        mvc.perform(MockMvcRequestBuilders.post("/api/refunds/1/review/submit").contentType(MediaType.APPLICATION_JSON)
                .content(reason("no credentials given"))).andExpect(status().isUnauthorized());
    }

    // ── Case submission ──

    @Test
    void operatorFlagsRefundAndFullCaseIsPersisted() throws Exception {
        Refund r = createRefund("ORD-SUB", "1500");
        RefundStatus before = r.getDerivedStatus();
        assertNotEquals(RefundStatus.ON_HOLD, before);

        RefundReview rv = submit(r);
        assertEquals(RefundStatus.ON_HOLD, refundStatus(r));
        assertEquals(ReviewStatus.PENDING, rv.getStatus());
        assertEquals(RefundReview.ORIGIN_MANUAL, rv.getOrigin());
        assertEquals("operator", rv.getSubmittedBy());
        assertEquals("OPERATOR", rv.getSubmittedByRole());
        assertEquals(FLAG_REASON, rv.getSubmissionReason());
        assertEquals(before, rv.getPreviousStatus());
        assertNotNull(rv.getHeldAt());
        assertNotNull(rv.getHoldAssessmentId());
        assertTrue(openReviewCase(r));
        assertTrue(hasEvent(r, "review.submitted"));
    }

    @Test
    void submissionRequiresReasonAndSubmitPermission() throws Exception {
        Refund r = createRefund("ORD-NOREASON", "1500");
        RefundStatus before = r.getDerivedStatus();
        post("/api/refunds/" + r.getId() + "/review/submit", "operator", OPERATOR_PW, "{}").andExpect(status().isBadRequest());
        post("/api/refunds/" + r.getId() + "/review/submit", "operator", OPERATOR_PW, reason("   ")).andExpect(status().isBadRequest());
        post("/api/refunds/" + r.getId() + "/review/submit", "reviewer", REVIEWER_PW, reason(FLAG_REASON)).andExpect(status().isForbidden());
        post("/api/refunds/999999/review/submit", "operator", OPERATOR_PW, reason(FLAG_REASON)).andExpect(status().isNotFound());
        assertEquals(before, refundStatus(r));
        assertFalse(reviews.existsByRefundId(r.getId()));
    }

    @Test
    void duplicateSubmissionIsRejected() throws Exception {
        Refund r = createRefund("ORD-DUP", "1500");
        submit(r);
        post("/api/refunds/" + r.getId() + "/review/submit", "admin", ADMIN_PW, reason("Second flag of the same refund")).andExpect(status().isConflict());
        assertEquals(1, reviews.findByRefundIdOrderByHeldAtDesc(r.getId()).size());
        assertEquals(1, countEvents(r, "review.submitted"));
    }

    @Test
    void settledRefundCannotBeSubmitted() throws Exception {
        Refund r = createRefund("ORD-SETTLED", "1500");
        r.setDerivedStatus(RefundStatus.SETTLEMENT_MATCHED);
        refunds.save(r);
        post("/api/refunds/" + r.getId() + "/review/submit", "operator", OPERATOR_PW, reason(FLAG_REASON)).andExpect(status().isConflict());
        assertEquals(RefundStatus.SETTLEMENT_MATCHED, refundStatus(r));
        assertFalse(reviews.existsByRefundId(r.getId()));
    }

    @Test
    void clientSuppliedIdentityIsIgnoredOnSubmission() throws Exception {
        Refund r = createRefund("ORD-SPOOF2", "1500");
        post("/api/refunds/" + r.getId() + "/review/submit", "operator", OPERATOR_PW,
                "{\"reason\":\"" + FLAG_REASON + "\",\"submittedBy\":\"admin\",\"role\":\"ADMIN\"}").andExpect(status().isOk());
        RefundReview rv = reviews.findByRefundIdOrderByHeldAtDesc(r.getId()).get(0);
        assertEquals("operator", rv.getSubmittedBy());
        assertEquals("OPERATOR", rv.getSubmittedByRole());
    }

    // ── Case visibility ──

    @Test
    void submittedCaseAppearsInReviewerQueueWithFullContext() throws Exception {
        Refund r = createRefund("ORD-VIS", "1500");
        RefundReview rv = submit(r);

        mvc.perform(get("/api/reviews?status=OPEN").with(httpBasic("reviewer", REVIEWER_PW)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].reviewId", hasItem(rv.getId().intValue())))
                .andExpect(jsonPath("$[0].origin").value("MANUAL"))
                .andExpect(jsonPath("$[0].submittedBy").value("operator"))
                .andExpect(jsonPath("$[0].allowedActions", hasItem("START")));

        mvc.perform(get("/api/reviews/" + rv.getId()).with(httpBasic("reviewer", REVIEWER_PW)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.review.status").value("PENDING"))
                .andExpect(jsonPath("$.review.submissionReason").value(FLAG_REASON))
                .andExpect(jsonPath("$.refund.id").value(r.getId().intValue()))
                .andExpect(jsonPath("$.order.externalOrderId").value("ORD-VIS"))
                .andExpect(jsonPath("$.risk.current.score").exists())
                .andExpect(jsonPath("$.indicators.slaState").exists())
                .andExpect(jsonPath("$.relatedCases[*].issueType", hasItem("HIGH_RISK_REVIEW")))
                .andExpect(jsonPath("$.timeline[*].eventType", hasItem("review.submitted")))
                .andExpect(jsonPath("$.activity[0].type").value("review.submitted"))
                .andExpect(jsonPath("$.activity[0].actor").value("operator"))
                .andExpect(jsonPath("$.allowedActions", hasItem("APPROVE")))
                .andExpect(jsonPath("$.releaseMode").value("SIMULATED"));

        // Operators can see the case but are offered no reviewer actions.
        mvc.perform(get("/api/reviews/" + rv.getId()).with(httpBasic("operator", OPERATOR_PW)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowedActions", not(hasItem("APPROVE"))))
                .andExpect(jsonPath("$.allowedActions", not(hasItem("START"))))
                .andExpect(jsonPath("$.allowedActions", hasItem("NOTE")));

        mvc.perform(get("/api/reviews/summary").with(httpBasic("reviewer", REVIEWER_PW)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pending").value(1))
                .andExpect(jsonPath("$.open").value(1))
                .andExpect(jsonPath("$.completed").value(0));

        mvc.perform(get("/api/reviews/999999").with(httpBasic("reviewer", REVIEWER_PW))).andExpect(status().isNotFound());
    }

    @Test
    void autoHeldRefundsStillAppearAsRiskEngineCases() throws Exception {
        Refund r = createRefund("ORD-AUTO", "150000"); // auto-held by the existing risk rule
        assertEquals(RefundStatus.ON_HOLD, r.getDerivedStatus());
        mvc.perform(get("/api/reviews?status=PENDING").with(httpBasic("reviewer", REVIEWER_PW)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].origin").value("AUTO_RISK"))
                .andExpect(jsonPath("$[0].submittedBy").value("SYSTEM"));
    }

    // ── State transitions + audit ──

    @Test
    void fullLifecycleWithMoreInfoResubmissionAndSimulatedRelease() throws Exception {
        Refund r = createRefund("ORD-LIFE", "1500");
        RefundStatus before = r.getDerivedStatus();
        Long id = submit(r).getId();

        act(id, "start", "reviewer", REVIEWER_PW, "Picking this up").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UNDER_REVIEW"));
        assertEquals("reviewer", review(id).getAssignedTo());

        act(id, "request-info", "reviewer", REVIEWER_PW, "Please attach the bank statement").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("MORE_INFO_REQUIRED"));
        assertEquals(RefundStatus.ON_HOLD, refundStatus(r)); // never released while waiting for information
        assertEquals("Please attach the bank statement", review(id).getInfoRequestReason());

        // cannot be decided until operations resubmit
        act(id, "approve", "reviewer", REVIEWER_PW, "Approving too early").andExpect(status().isConflict());
        post("/api/refunds/" + r.getId() + "/review/approve", "reviewer", REVIEWER_PW, reason("Approving too early")).andExpect(status().isConflict());

        act(id, "resubmit", "operator", OPERATOR_PW, "Bank statement attached, amount verified").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));
        assertEquals(1, review(id).getResubmissionCount());
        assertNull(review(id).getAssignedTo());

        act(id, "start", "reviewer", REVIEWER_PW, "Second look").andExpect(status().isOk());
        act(id, "approve", "reviewer", REVIEWER_PW, "Verified with bank statement").andExpect(status().isOk())
                .andExpect(jsonPath("$.simulatedRelease").value(true));

        RefundReview done = review(id);
        assertEquals(ReviewStatus.APPROVED, done.getStatus());
        assertEquals("reviewer", done.getDecidedBy());
        assertEquals("REVIEWER", done.getDecidedByRole());
        assertEquals("Verified with bank statement", done.getDecisionReason());
        assertTrue(done.getSimulatedRelease());
        assertEquals(before, refundStatus(r));
        assertFalse(openReviewCase(r));

        for (String type : List.of("review.submitted", "review.started", "review.info_requested", "review.resubmitted",
                "review.approved", "review.released.simulated")) {
            assertTrue(hasEvent(r, type), "missing audit event " + type);
        }
        assertEquals(2, countEvents(r, "review.started"));

        // closed case: nothing more can happen
        act(id, "reject", "reviewer", REVIEWER_PW, "Changing my mind").andExpect(status().isConflict());
        mvc.perform(MockMvcRequestBuilders.post("/api/reviews/" + id + "/notes").with(httpBasic("reviewer", REVIEWER_PW))
                .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"late note on closed case\"}")).andExpect(status().isConflict());
    }

    @Test
    void decisionsAndRequestsRequireAReason() throws Exception {
        Refund r = createRefund("ORD-REASON", "1500");
        Long id = submit(r).getId();
        for (String path : List.of("approve", "reject", "escalate", "request-info")) {
            post("/api/reviews/" + id + "/" + path, "reviewer", REVIEWER_PW, "{}").andExpect(status().isBadRequest());
            post("/api/reviews/" + id + "/" + path, "reviewer", REVIEWER_PW, reason("ok")).andExpect(status().isBadRequest());
        }
        assertEquals(ReviewStatus.PENDING, review(id).getStatus());
        // START needs no reason
        post("/api/reviews/" + id + "/start", "reviewer", REVIEWER_PW, "{}").andExpect(status().isOk());
    }

    // ── Decision permissions ──

    @Test
    void operatorCannotPerformReviewerActions() throws Exception {
        Refund r = createRefund("ORD-OPS", "1500");
        Long id = submit(r).getId();
        for (String path : List.of("start", "request-info", "escalate", "approve", "reject")) {
            act(id, path, "operator", OPERATOR_PW, "Operator attempting " + path).andExpect(status().isForbidden());
        }
        post("/api/refunds/" + r.getId() + "/review/approve", "operator", OPERATOR_PW, reason("Operator approving")).andExpect(status().isForbidden());
        assertEquals(ReviewStatus.PENDING, review(id).getStatus());
        assertEquals(RefundStatus.ON_HOLD, refundStatus(r));
        // operators may add investigation notes
        mvc.perform(MockMvcRequestBuilders.post("/api/reviews/" + id + "/notes").with(httpBasic("operator", OPERATOR_PW))
                .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"Customer called again\"}")).andExpect(status().isOk());
    }

    @Test
    void reviewerCannotResubmitOnBehalfOfOperations() throws Exception {
        Refund r = createRefund("ORD-RESUB", "1500");
        Long id = submit(r).getId();
        act(id, "request-info", "reviewer", REVIEWER_PW, "Need the customer's KYC").andExpect(status().isOk());
        act(id, "resubmit", "reviewer", REVIEWER_PW, "Resubmitting myself").andExpect(status().isForbidden());
        assertEquals(ReviewStatus.MORE_INFO_REQUIRED, review(id).getStatus());
    }

    @Test
    void escalatedCaseCanOnlyBeDecidedByAdmin() throws Exception {
        Refund r = createRefund("ORD-ESC", "1500");
        Long id = submit(r).getId();
        act(id, "escalate", "reviewer", REVIEWER_PW, "Large exposure, needs admin sign-off").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ESCALATED"));
        assertEquals("reviewer", review(id).getEscalatedBy());

        act(id, "approve", "reviewer", REVIEWER_PW, "Trying to approve anyway").andExpect(status().isForbidden());
        act(id, "reject", "reviewer2", REVIEWER2_PW, "Trying to reject anyway").andExpect(status().isForbidden());
        act(id, "start", "reviewer", REVIEWER_PW, "Restart").andExpect(status().isConflict());
        assertEquals(ReviewStatus.ESCALATED, review(id).getStatus());

        act(id, "reject", "admin", ADMIN_PW, "Admin rejects after escalation").andExpect(status().isOk());
        assertEquals(ReviewStatus.REJECTED, review(id).getStatus());
        assertEquals(RefundStatus.REJECTED, refundStatus(r));
        assertFalse(openReviewCase(r));
        assertTrue(hasEvent(r, "review.escalated"));
        assertTrue(hasEvent(r, "review.rejected"));
    }

    @Test
    void caseUnderReviewCannotBeTakenOverByAnotherReviewer() throws Exception {
        Refund r = createRefund("ORD-ASSIGN", "1500");
        Long id = submit(r).getId();
        act(id, "start", "reviewer", REVIEWER_PW, "Taking this case").andExpect(status().isOk());
        act(id, "approve", "reviewer2", REVIEWER2_PW, "Approving someone else's case").andExpect(status().isConflict());
        act(id, "start", "reviewer2", REVIEWER2_PW, "Taking over").andExpect(status().isConflict());
        assertEquals(ReviewStatus.UNDER_REVIEW, review(id).getStatus());
        act(id, "approve", "reviewer", REVIEWER_PW, "Assigned reviewer approves").andExpect(status().isOk());
    }

    @Test
    void submitterCannotDecideOwnSubmissionFourEyes() throws Exception {
        Refund r = createRefund("ORD-4EYES", "1500");
        Long id = submitAs(r, "admin", ADMIN_PW).getId();
        act(id, "approve", "admin", ADMIN_PW, "Approving my own flag").andExpect(status().isForbidden());
        post("/api/refunds/" + r.getId() + "/review/approve", "admin", ADMIN_PW, reason("Approving my own flag")).andExpect(status().isForbidden());
        assertEquals(ReviewStatus.PENDING, review(id).getStatus());
        act(id, "approve", "reviewer", REVIEWER_PW, "Independent reviewer approves").andExpect(status().isOk());
    }

    @Test
    void genericCaseResolveCannotBypassManualReview() throws Exception {
        Refund r = createRefund("ORD-BYPASS2", "1500");
        submit(r);
        Case c = cases.findByRefundIdAndStatus(r.getId(), "OPEN").stream()
                .filter(x -> "HIGH_RISK_REVIEW".equals(x.getIssueType())).findFirst().orElseThrow();
        post("/api/cases/" + c.getId() + "/resolve", "operator", OPERATOR_PW, "{\"notes\":\"closing it myself\"}").andExpect(status().isConflict());
        assertEquals(RefundStatus.ON_HOLD, refundStatus(r));
    }

    // ── Notes ──

    @Test
    void investigationNotesAreAuditedAndShownInCaseActivity() throws Exception {
        Refund r = createRefund("ORD-NOTE", "1500");
        Long id = submit(r).getId();
        mvc.perform(MockMvcRequestBuilders.post("/api/reviews/" + id + "/notes").with(httpBasic("reviewer", REVIEWER_PW))
                .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"Called the bank, awaiting confirmation\"}"))
                .andExpect(status().isOk());
        mvc.perform(MockMvcRequestBuilders.post("/api/reviews/" + id + "/notes").with(httpBasic("reviewer", REVIEWER_PW))
                .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"\"}")).andExpect(status().isBadRequest());
        assertEquals(ReviewStatus.PENDING, review(id).getStatus()); // notes never change the state
        assertEquals(1, countEvents(r, "review.note"));
        mvc.perform(get("/api/reviews/" + id).with(httpBasic("reviewer", REVIEWER_PW)))
                .andExpect(jsonPath("$.activity[*].type", hasItem("review.note")))
                .andExpect(jsonPath("$.activity[*].text", hasItem("Called the bank, awaiting confirmation")));
    }

    // ── Concurrency ──

    @Test
    void concurrentConflictingDecisionsProduceExactlyOneOutcome() throws Exception {
        Refund r = createRefund("ORD-RACE", "1500");
        Long id = submit(r).getId();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        Callable<Boolean> approve = () -> {
            go.await();
            try {
                reviewService.act(id, ReviewAction.APPROVE, "Parallel approval", new Actor("reviewer", Role.REVIEWER));
                return true;
            } catch (Exception e) {
                return false;
            }
        };
        Callable<Boolean> reject = () -> {
            go.await();
            try {
                reviewService.act(id, ReviewAction.REJECT, "Parallel rejection", new Actor("reviewer2", Role.REVIEWER));
                return true;
            } catch (Exception e) {
                return false;
            }
        };
        List<Future<Boolean>> results = new ArrayList<>();
        results.add(pool.submit(approve));
        results.add(pool.submit(reject));
        go.countDown();
        int ok = 0;
        for (Future<Boolean> f : results) if (f.get(30, TimeUnit.SECONDS)) ok++;
        pool.shutdown();

        assertEquals(1, ok, "exactly one of two concurrent decisions may succeed");
        RefundReview done = review(id);
        assertTrue(done.getStatus() == ReviewStatus.APPROVED || done.getStatus() == ReviewStatus.REJECTED);
        assertEquals(1, countEvents(r, "review.approved") + countEvents(r, "review.rejected"));
        RefundStatus rs = refundStatus(r);
        if (done.getStatus() == ReviewStatus.APPROVED) assertNotEquals(RefundStatus.ON_HOLD, rs);
        else assertEquals(RefundStatus.REJECTED, rs);
    }

    @Test
    void summaryCountsComeFromPersistedCases() throws Exception {
        Refund a = createRefund("ORD-SUM-A", "1500");
        Refund b = createRefund("ORD-SUM-B", "1500");
        Refund c = createRefund("ORD-SUM-C", "1500");
        submit(a);
        Long bId = submit(b).getId();
        Long cId = submit(c).getId();
        act(bId, "escalate", "reviewer", REVIEWER_PW, "Needs admin sign-off").andExpect(status().isOk());
        act(cId, "reject", "reviewer", REVIEWER_PW, "Clearly fraudulent").andExpect(status().isOk());
        mvc.perform(get("/api/reviews/summary").with(httpBasic("reviewer", REVIEWER_PW)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pending").value(1))
                .andExpect(jsonPath("$.escalated").value(1))
                .andExpect(jsonPath("$.open").value(2))
                .andExpect(jsonPath("$.completed").value(1))
                .andExpect(jsonPath("$.rejected").value(1));
    }
}
