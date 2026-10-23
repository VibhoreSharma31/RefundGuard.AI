package com.refundguard.controller;

import com.refundguard.exception.ApiException;
import com.refundguard.model.*;
import com.refundguard.repository.RefundRepository;
import com.refundguard.repository.RefundReviewRepository;
import com.refundguard.review.RefundReviewService;
import com.refundguard.review.ReviewAction;
import com.refundguard.review.ReviewCaseViewService;
import com.refundguard.review.ReviewDecision;
import com.refundguard.risk.RiskConfig;
import com.refundguard.risk.RiskScoringEngine;
import com.refundguard.risk.RiskScoringService;
import com.refundguard.security.Actor;
import com.refundguard.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Risk assessment, hold-and-release and review-case endpoints.
 * Identity and role always come from the authenticated context; request bodies carry only the reason / note text.
 * Coarse access is enforced here with @PreAuthorize; state- and case-specific rules (escalation, assignment,
 * four-eyes, legal transitions) are enforced in RefundReviewService.
 */
@RestController
@RequestMapping("/api")
@Tag(name = "Risk & Review", description = "Rule-based refund risk scoring, the hold-and-release approval workflow and reviewer case management")
public class RiskReviewController {

    /** Only the reason is accepted from the client; any other JSON property (e.g. reviewer, role) is ignored. */
    public record DecisionRequest(String reason) {}

    /** Text for notes; "reason" is accepted as an alias. */
    public record NoteRequest(String note, String reason) {
        String text() { return note != null ? note : reason; }
    }

    private final RefundRepository refundRepository;
    private final RefundReviewRepository reviewRepository;
    private final RiskScoringService riskService;
    private final RefundReviewService reviewService;
    private final ReviewCaseViewService views;

    public RiskReviewController(RefundRepository refundRepository, RefundReviewRepository reviewRepository,
                                RiskScoringService riskService, RefundReviewService reviewService,
                                ReviewCaseViewService views) {
        this.refundRepository = refundRepository;
        this.reviewRepository = reviewRepository;
        this.riskService = riskService;
        this.reviewService = reviewService;
        this.views = views;
    }

    // ───────────────────────── risk ─────────────────────────

    @GetMapping("/risk/methodology")
    @PreAuthorize("hasAuthority('RISK_VIEW')")
    @Operation(summary = "How refund risk is calculated (rule-based, not machine learning) and the active thresholds")
    public ResponseEntity<Map<String, Object>> methodology() {
        RiskConfig c = riskService.config();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("method", "RULE_BASED");
        m.put("machineLearning", false);
        m.put("engineVersion", RiskScoringEngine.ENGINE_VERSION);
        m.put("description", "Transparent rule-based score from 0 to 100 using only data held in RefundGuard "
                + "(refund, order, gateway events, other refunds of the same customer e-mail, open cases). Not an ML model.");
        m.put("categories", Map.of("MEDIUM", c.getMediumMin(), "HIGH", c.getHighMin(), "CRITICAL", c.getCriticalMin()));
        m.put("holdEnabled", c.isHoldEnabled());
        m.put("holdMinScore", c.getHoldMinScore());
        m.put("maxAgeMinutes", c.getMaxAgeMinutes());
        return ResponseEntity.ok(m);
    }

    @GetMapping("/risk/latest")
    @PreAuthorize("hasAuthority('RISK_VIEW')")
    @Operation(summary = "Latest risk assessment summary for every refund (keyed by refund id)")
    public ResponseEntity<Map<Long, Map<String, Object>>> latest() {
        List<Refund> refunds = refundRepository.findAll();
        Map<Long, RiskAssessment> latest = riskService.latestFor(refunds.stream().map(Refund::getId).toList());
        Map<Long, Refund> byId = new HashMap<>();
        refunds.forEach(r -> byId.put(r.getId(), r));
        Map<Long, Map<String, Object>> out = new LinkedHashMap<>();
        latest.forEach((id, a) -> out.put(id, summary(a, riskService.staleness(a, byId.get(id), false))));
        return ResponseEntity.ok(out);
    }

    @GetMapping("/refunds/{id}/risk")
    @PreAuthorize("hasAuthority('RISK_VIEW')")
    @Operation(summary = "Current risk assessment (with reasons and staleness) plus recent history for a refund")
    public ResponseEntity<Map<String, Object>> risk(@PathVariable Long id) {
        Refund refund = refundRepository.findById(id).orElseThrow(() -> ApiException.notFound("Refund " + id + " not found."));
        Map<String, Object> out = new LinkedHashMap<>();
        Optional<RiskAssessment> latest = riskService.latest(id);
        out.put("refundId", id);
        out.put("method", "RULE_BASED");
        if (latest.isEmpty()) {
            out.put("assessed", false);
            out.put("message", "No risk assessment has been recorded for this refund yet. Use reassess to create one.");
            return ResponseEntity.ok(out);
        }
        out.put("assessed", true);
        out.put("current", riskService.view(latest.get(), riskService.staleness(latest.get(), refund, true), refund));
        List<Map<String, Object>> hist = new ArrayList<>();
        for (RiskAssessment a : riskService.history(id)) {
            Map<String, Object> h = new LinkedHashMap<>();
            h.put("assessmentId", a.getId());
            h.put("score", a.getScore());
            h.put("level", a.getLevel());
            h.put("assessedAt", a.getAssessedAt());
            h.put("trigger", a.getTrigger());
            hist.add(h);
        }
        out.put("history", hist);
        return ResponseEntity.ok(out);
    }

    @PostMapping("/refunds/{id}/risk/reassess")
    @PreAuthorize("hasAuthority('RISK_ASSESS')")
    @Operation(summary = "Recompute and store a fresh risk assessment (may place an eligible refund on hold)")
    public ResponseEntity<Map<String, Object>> reassess(@PathVariable Long id) {
        return ResponseEntity.ok(reviewService.reassess(id, CurrentUser.require()));
    }

    // ───────────────────────── review queue & cases ─────────────────────────

    @GetMapping("/reviews")
    @PreAuthorize("hasAuthority('REVIEW_VIEW')")
    @Operation(summary = "Review queue: status=PENDING (default), OPEN (all active cases), ALL (latest 100) or a single review status")
    public ResponseEntity<List<Map<String, Object>>> reviews(@RequestParam(defaultValue = "PENDING") String status) {
        return ResponseEntity.ok(views.queue(status, CurrentUser.require()));
    }

    @GetMapping("/reviews/summary")
    @PreAuthorize("hasAuthority('REVIEW_VIEW')")
    @Operation(summary = "Reviewer dashboard counts (pending, high risk, SLA breached, escalated, completed ...)")
    public ResponseEntity<Map<String, Object>> reviewSummary() {
        return ResponseEntity.ok(views.summary(CurrentUser.require()));
    }

    @GetMapping("/reviews/{reviewId}")
    @PreAuthorize("hasAuthority('REVIEW_VIEW')")
    @Operation(summary = "Full context of one review case: refund, order, risk, related cases, settlements, notes, audit timeline")
    public ResponseEntity<Map<String, Object>> reviewCase(@PathVariable Long reviewId) {
        return ResponseEntity.ok(views.detail(reviewId, CurrentUser.require()));
    }

    @PostMapping("/refunds/{id}/review/submit")
    @PreAuthorize("hasAuthority('REVIEW_SUBMIT')")
    @Operation(summary = "Flag a refund as risky and submit it for review (reason required). The refund is placed ON HOLD.")
    public ResponseEntity<Map<String, Object>> submit(@PathVariable Long id, @RequestBody DecisionRequest body) {
        return ResponseEntity.ok(reviewService.submitForReview(id, body.reason(), CurrentUser.require()));
    }

    @PostMapping("/reviews/{reviewId}/start")
    @PreAuthorize("hasAuthority('REVIEW_DECIDE')")
    @Operation(summary = "Start reviewing a pending case (assigns it to the caller)")
    public ResponseEntity<Map<String, Object>> start(@PathVariable Long reviewId, @RequestBody(required = false) DecisionRequest body) {
        return ResponseEntity.ok(reviewService.act(reviewId, ReviewAction.START, body == null ? null : body.reason(), CurrentUser.require()));
    }

    @PostMapping("/reviews/{reviewId}/request-info")
    @PreAuthorize("hasAuthority('REVIEW_DECIDE')")
    @Operation(summary = "Return the case to operations for more information (reason required). The refund stays on hold.")
    public ResponseEntity<Map<String, Object>> requestInfo(@PathVariable Long reviewId, @RequestBody DecisionRequest body) {
        return ResponseEntity.ok(reviewService.act(reviewId, ReviewAction.REQUEST_INFO, body.reason(), CurrentUser.require()));
    }

    @PostMapping("/reviews/{reviewId}/resubmit")
    @PreAuthorize("hasAuthority('REVIEW_SUBMIT')")
    @Operation(summary = "Resubmit a case after more information was requested (the added information is required)")
    public ResponseEntity<Map<String, Object>> resubmit(@PathVariable Long reviewId, @RequestBody DecisionRequest body) {
        return ResponseEntity.ok(reviewService.act(reviewId, ReviewAction.RESUBMIT, body.reason(), CurrentUser.require()));
    }

    @PostMapping("/reviews/{reviewId}/escalate")
    @PreAuthorize("hasAuthority('REVIEW_DECIDE')")
    @Operation(summary = "Escalate the case to an ADMIN (reason required)")
    public ResponseEntity<Map<String, Object>> escalate(@PathVariable Long reviewId, @RequestBody DecisionRequest body) {
        return ResponseEntity.ok(reviewService.act(reviewId, ReviewAction.ESCALATE, body.reason(), CurrentUser.require()));
    }

    @PostMapping("/reviews/{reviewId}/approve")
    @PreAuthorize("hasAuthority('REVIEW_DECIDE')")
    @Operation(summary = "Approve release (reason required). Release is SIMULATED - no real payment is made.")
    public ResponseEntity<Map<String, Object>> approveCase(@PathVariable Long reviewId, @RequestBody DecisionRequest body) {
        return ResponseEntity.ok(reviewService.act(reviewId, ReviewAction.APPROVE, body.reason(), CurrentUser.require()));
    }

    @PostMapping("/reviews/{reviewId}/reject")
    @PreAuthorize("hasAuthority('REVIEW_DECIDE')")
    @Operation(summary = "Reject release (reason required). The refund becomes REJECTED and will not be released.")
    public ResponseEntity<Map<String, Object>> rejectCase(@PathVariable Long reviewId, @RequestBody DecisionRequest body) {
        return ResponseEntity.ok(reviewService.act(reviewId, ReviewAction.REJECT, body.reason(), CurrentUser.require()));
    }

    @PostMapping("/reviews/{reviewId}/notes")
    @PreAuthorize("hasAnyAuthority('REVIEW_DECIDE','REVIEW_SUBMIT')")
    @Operation(summary = "Add an investigation note to an open case (recorded in the audit ledger)")
    public ResponseEntity<Map<String, Object>> note(@PathVariable Long reviewId, @RequestBody NoteRequest body) {
        return ResponseEntity.ok(reviewService.act(reviewId, ReviewAction.NOTE, body.text(), CurrentUser.require()));
    }

    // ───────────────────────── refund-scoped review endpoints (existing API) ─────────────────────────

    @GetMapping("/refunds/{id}/review")
    @PreAuthorize("hasAuthority('REVIEW_VIEW')")
    @Operation(summary = "Review history of one refund and what the caller may do right now")
    public ResponseEntity<Map<String, Object>> reviewOf(@PathVariable Long id) {
        Refund refund = refundRepository.findById(id).orElseThrow(() -> ApiException.notFound("Refund " + id + " not found."));
        Actor actor = CurrentUser.require();
        List<Map<String, Object>> history = new ArrayList<>();
        for (RefundReview rv : reviewRepository.findByRefundIdOrderByHeldAtDesc(id)) history.add(ReviewCaseViewService.reviewMap(rv));
        Map<String, Object> state = views.refundReviewState(refund, actor);
        @SuppressWarnings("unchecked")
        List<String> allowed = (List<String>) state.get("allowedActions");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("refund", ReviewCaseViewService.refundSummary(refund));
        out.put("reviews", history);
        out.put("canDecide", allowed.contains(ReviewAction.APPROVE.name()));
        out.putAll(state);
        return ResponseEntity.ok(out);
    }

    @PostMapping("/refunds/{id}/review/approve")
    @PreAuthorize("hasAuthority('REVIEW_DECIDE')")
    @Operation(summary = "Approve a held refund (reason required). Release is SIMULATED - no real payment is made.")
    public ResponseEntity<Map<String, Object>> approve(@PathVariable Long id, @RequestBody DecisionRequest body) {
        return ResponseEntity.ok(reviewService.decide(id, ReviewDecision.APPROVE, body.reason(), CurrentUser.require()));
    }

    @PostMapping("/refunds/{id}/review/reject")
    @PreAuthorize("hasAuthority('REVIEW_DECIDE')")
    @Operation(summary = "Reject a held refund (reason required)")
    public ResponseEntity<Map<String, Object>> reject(@PathVariable Long id, @RequestBody DecisionRequest body) {
        return ResponseEntity.ok(reviewService.decide(id, ReviewDecision.REJECT, body.reason(), CurrentUser.require()));
    }

    // ───────────────────────── mapping helpers ─────────────────────────

    private Map<String, Object> summary(RiskAssessment a, RiskScoringService.Staleness st) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("score", a.getScore());
        m.put("level", a.getLevel());
        m.put("assessedAt", a.getAssessedAt());
        m.put("stale", st.stale());
        m.put("staleReason", st.reason());
        m.put("reasons", riskService.reasonsSummary(a));
        return m;
    }
}
