package com.refundguard.review;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.refundguard.exception.ApiException;
import com.refundguard.model.*;
import com.refundguard.repository.*;
import com.refundguard.risk.RiskScoringService;
import com.refundguard.security.Actor;
import com.refundguard.security.Permission;
import com.refundguard.security.Role;
import com.refundguard.security.RolePermissions;
import com.refundguard.service.CaseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Hold-and-release workflow and review cases.
 *
 *   new refund -> risk assessment -> score >= hold threshold -> refund ON_HOLD + review PENDING + HIGH_RISK_REVIEW case
 *   operator/admin "submit for review" (reason mandatory)   -> refund ON_HOLD + review PENDING + HIGH_RISK_REVIEW case
 *
 *   reviewer/admin actions (reason mandatory except for START):
 *       START        PENDING -> UNDER_REVIEW (assigned to the reviewer)
 *       REQUEST_INFO PENDING/UNDER_REVIEW/ESCALATED -> MORE_INFO_REQUIRED (back to operations, refund stays ON_HOLD)
 *       RESUBMIT     MORE_INFO_REQUIRED -> PENDING (operator/admin)
 *       ESCALATE     PENDING/UNDER_REVIEW -> ESCALATED (then only an ADMIN may act)
 *       APPROVE      -> refund returns to its pre-hold status, release is SIMULATED, case resolved
 *       REJECT       -> refund REJECTED (terminal), case resolved
 *
 * Every action locks the refund row first, so concurrent actions on the same refund are serialised; the loser
 * re-reads the case after the lock and gets 409. Every transition is written to the refund timeline
 * (rg_refund_events, source REVIEW_WORKFLOW). No real payment system is integrated: "release" never moves money.
 */
@Service
public class RefundReviewService {

    private static final Logger log = LoggerFactory.getLogger(RefundReviewService.class);

    public static final String SOURCE = "REVIEW_WORKFLOW";
    public static final String CASE_TYPE = "HIGH_RISK_REVIEW";

    private final RefundRepository refundRepository;
    private final RefundReviewRepository reviewRepository;
    private final RefundEventRepository eventRepository;
    private final RiskScoringService riskService;
    private final CaseService caseService;
    private final ObjectMapper mapper;

    public RefundReviewService(RefundRepository refundRepository, RefundReviewRepository reviewRepository,
                               RefundEventRepository eventRepository, RiskScoringService riskService,
                               CaseService caseService, ObjectMapper mapper) {
        this.refundRepository = refundRepository;
        this.reviewRepository = reviewRepository;
        this.eventRepository = eventRepository;
        this.riskService = riskService;
        this.caseService = caseService;
        this.mapper = mapper;
    }

    /**
     * Scores the refund and, if it meets the configured hold criteria, places it on hold.
     * Runs inside the caller's transaction. A refund that already has a review (decided or pending)
     * is never held a second time.
     */
    @Transactional
    public Optional<RefundReview> evaluateAndHold(Refund refund, String trigger) {
        if (refund.getId() == null) return Optional.empty();
        if (!ReviewStateMachine.canHold(refund.getDerivedStatus())) return Optional.empty();
        if (reviewRepository.existsByRefundId(refund.getId())) return Optional.empty();

        RiskAssessment a = riskService.assessAndPersist(refund, trigger);
        if (!riskService.config().wouldHold(a.getScore(), a.isPolicyViolation())) return Optional.empty();

        RefundStatus previous = refund.getDerivedStatus();
        refund.setDerivedStatus(RefundStatus.ON_HOLD);
        refund.setLastEventAt(LocalDateTime.now());
        refundRepository.save(refund);

        String reasons = riskService.reasonsSummary(a);
        RefundReview rv = new RefundReview();
        rv.setRefundId(refund.getId());
        rv.setStatus(ReviewStatus.PENDING);
        rv.setPreviousStatus(previous);
        rv.setHoldAssessmentId(a.getId());
        String why = riskService.config().shouldHold(a.getScore())
                ? "reached the hold threshold " + riskService.config().getHoldMinScore() + "."
                : "is below the hold threshold, but a policy violation requires reviewer approval.";
        rv.setHoldReason(trim("Risk score " + a.getScore() + "/100 (" + a.getLevel() + ") " + why + " " + reasons, 1000));
        rv.setHeldAt(LocalDateTime.now());
        rv.setOrigin(RefundReview.ORIGIN_AUTO);
        rv.setSubmittedBy("SYSTEM");
        rv.setResubmissionCount(0);
        rv.setLastActionAt(rv.getHeldAt());
        rv = reviewRepository.save(rv);

        caseService.openHighRiskReviewCase(refund, a.getScore(), a.getLevel().name(), reasons);
        audit(refund.getId(), "review.held", "SYSTEM", null,
                "Refund placed ON HOLD for review (risk " + a.getScore() + "/100 " + a.getLevel() + ").", reasons, a.getId(), rv.getId(), null);
        log.warn("Refund {} placed ON_HOLD (risk {} {}) reviewId={}", refund.getId(), a.getScore(), a.getLevel(), rv.getId());
        return Optional.of(rv);
    }

    /**
     * Operator/admin flags a refund as risky and submits it for review. The refund is placed ON_HOLD (so it cannot
     * progress to release) and a review case is opened. Duplicate submissions (an open case already exists) get 409.
     */
    @Transactional
    public Map<String, Object> submitForReview(Long refundId, String reason, Actor actor) {
        if (!RolePermissions.has(actor.role(), Permission.REVIEW_SUBMIT)) {
            throw ApiException.forbidden("Only an OPERATOR or ADMIN can submit a refund for review.");
        }
        String clean = requireReason(reason);
        Refund refund = lock(refundId);

        Optional<RefundReview> open = reviewRepository.findFirstByRefundIdAndStatusIn(refundId, ReviewStateMachine.openStatuses());
        if (open.isPresent()) {
            throw ApiException.conflict("Refund " + refundId + " already has an open review case (#" + open.get().getId()
                    + ", " + open.get().getStatus() + ").");
        }
        RefundStatus previous = refund.getDerivedStatus();
        if (!ReviewStateMachine.canSubmit(previous)) {
            throw ApiException.conflict("Refund " + refundId + " is " + previous + " and cannot be held for review. "
                    + "Only refunds that are not yet settled, closed, rejected or on hold can be submitted.");
        }

        // Fresh assessment so the reviewer sees the risk at submission time.
        RiskAssessment a = riskService.assessAndPersist(refund, RiskScoringService.TRIGGER_MANUAL);
        LocalDateTime now = LocalDateTime.now();
        refund.setDerivedStatus(RefundStatus.ON_HOLD);
        refund.setLastEventAt(now);
        refundRepository.save(refund);

        RefundReview rv = new RefundReview();
        rv.setRefundId(refundId);
        rv.setStatus(ReviewStatus.PENDING);
        rv.setPreviousStatus(previous);
        rv.setHoldAssessmentId(a.getId());
        rv.setHoldReason(trim("Submitted for review by " + actor.username() + " (" + actor.role() + "): " + clean, 1000));
        rv.setHeldAt(now);
        rv.setOrigin(RefundReview.ORIGIN_MANUAL);
        rv.setSubmittedBy(actor.username());
        rv.setSubmittedByRole(actor.role().name());
        rv.setSubmissionReason(clean);
        rv.setResubmissionCount(0);
        rv.setLastActionAt(now);
        rv = reviewRepository.save(rv);

        caseService.openManualReviewCase(refund, actor.username(), clean, a.getScore(), a.getLevel().name());
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("action", "SUBMIT");
        extra.put("text", clean);
        extra.put("previousStatus", previous.name());
        extra.put("reviewStatus", rv.getStatus().name());
        audit(refundId, "review.submitted", actor.username(), actor.role().name(),
                "Refund submitted for review and placed ON HOLD by " + actor.username() + " (" + actor.role() + "), was "
                        + previous + ". Reason: " + clean, riskService.reasonsSummary(a), a.getId(), rv.getId(), extra);
        log.warn("Refund {} submitted for review by {} ({}) reviewId={}", refundId, actor.username(), actor.role(), rv.getId());
        return result(rv, refund, "Submitted for review. The refund is ON HOLD until a reviewer decides.");
    }

    /**
     * Record a reviewer's decision on the open review case of a refund (legacy refund-scoped endpoint).
     * The refund row is locked (SELECT ... FOR UPDATE) so concurrent decisions are serialised; the loser gets 409.
     */
    @Transactional
    public Map<String, Object> decide(Long refundId, ReviewDecision decision, String reason, Actor actor) {
        if (decision == null) throw ApiException.badRequest("A decision (APPROVE or REJECT) is required.");
        String cleanReason = requireReason(reason);

        Refund refund = lock(refundId);
        RefundReview rv = reviewRepository.findFirstByRefundIdAndStatusIn(refundId, ReviewStateMachine.openStatuses()).orElse(null);
        if (rv == null) {
            throw ApiException.conflict("Refund " + refundId + " is not awaiting review (current status: "
                    + refund.getDerivedStatus() + "). A decision may already have been recorded.");
        }
        return applyDecision(rv, refund, decision, cleanReason, actor);
    }

    /** Any action on a review case, addressed by the case id. See the class comment for the rules. */
    @Transactional
    public Map<String, Object> act(Long reviewId, ReviewAction action, String text, Actor actor) {
        if (action == null) throw ApiException.badRequest("An action is required.");
        String clean = (action == ReviewAction.START && (text == null || text.isBlank())) ? null : requireReason(text);

        Long refundId = reviewRepository.findRefundIdById(reviewId)
                .orElseThrow(() -> ApiException.notFound("Review case " + reviewId + " not found."));
        Refund refund = lock(refundId);
        // Read the case only after the lock, so a concurrent action that just committed is visible.
        RefundReview rv = reviewRepository.findById(reviewId)
                .orElseThrow(() -> ApiException.notFound("Review case " + reviewId + " not found."));

        return switch (action) {
            case APPROVE -> applyDecision(rv, refund, ReviewDecision.APPROVE, clean, actor);
            case REJECT -> applyDecision(rv, refund, ReviewDecision.REJECT, clean, actor);
            default -> applyTransition(rv, refund, action, clean, actor);
        };
    }

    private Map<String, Object> applyDecision(RefundReview rv, Refund refund, ReviewDecision decision, String cleanReason, Actor actor) {
        Long refundId = refund.getId();
        if (!ReviewStateMachine.canDecide(refund.getDerivedStatus(), rv.getStatus())) {
            throw ApiException.conflict("Refund " + refundId + " is not awaiting review (current status: "
                    + refund.getDerivedStatus() + ", review case " + rv.getStatus() + "). A decision may already have been recorded.");
        }
        boolean approve = decision == ReviewDecision.APPROVE;
        ApiException denied = denial(rv, refund, approve ? ReviewAction.APPROVE : ReviewAction.REJECT, actor);
        if (denied != null) throw denied;

        // Re-score at decision time so the decision is linked to a current assessment.
        RiskAssessment current = riskService.assessAndPersist(refund, RiskScoringService.TRIGGER_DECISION);
        LocalDateTime now = LocalDateTime.now();

        rv.setStatus(decision.toStatus());
        rv.setDecision(decision);
        rv.setDecidedBy(actor.username());
        rv.setDecidedByRole(actor.role().name());
        rv.setDecisionReason(cleanReason);
        rv.setDecidedAt(now);
        rv.setDecisionAssessmentId(current.getId());
        rv.setSimulatedRelease(approve);
        rv.setLastActionAt(now);
        reviewRepository.save(rv);

        refund.setDerivedStatus(ReviewStateMachine.statusAfter(decision, rv.getPreviousStatus()));
        refund.setLastEventAt(now);
        refundRepository.save(refund);

        String verb = approve ? "approved" : "rejected";
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("action", approve ? "APPROVE" : "REJECT");
        extra.put("text", cleanReason);
        extra.put("reviewStatus", rv.getStatus().name());
        audit(refundId, approve ? "review.approved" : "review.rejected", actor.username(), actor.role().name(),
                "Refund " + verb + " by " + actor.username() + " (" + actor.role() + "). Reason: " + cleanReason,
                riskService.reasonsSummary(current), current.getId(), rv.getId(), extra);
        if (approve) {
            audit(refundId, "review.released.simulated", "SYSTEM", null,
                    "SIMULATED release: refund returned to normal processing as " + refund.getDerivedStatus()
                            + ". No real payment was initiated by RefundGuard.", null, current.getId(), rv.getId(), null);
        }
        caseService.resolveHighRiskReviewCase(refundId, "[REVIEW] " + verb + " by " + actor.username() + ": " + cleanReason);

        log.info("Review {} for refund {} {} by {} ({})", rv.getId(), refundId, verb, actor.username(), actor.role());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("refundId", refundId);
        out.put("reviewId", rv.getId());
        out.put("decision", decision);
        out.put("status", rv.getStatus());
        out.put("refundStatus", refund.getDerivedStatus());
        out.put("decidedBy", actor.username());
        out.put("decidedAt", now);
        out.put("riskScoreAtDecision", current.getScore());
        out.put("riskLevelAtDecision", current.getLevel());
        out.put("simulatedRelease", approve);
        out.put("message", approve
                ? "Approved. Release is SIMULATED - no real payment was initiated; the refund continues through normal processing."
                : "Rejected. The refund will not be released.");
        return out;
    }

    private Map<String, Object> applyTransition(RefundReview rv, Refund refund, ReviewAction action, String text, Actor actor) {
        ApiException denied = denial(rv, refund, action, actor);
        if (denied != null) throw denied;

        LocalDateTime now = LocalDateTime.now();
        Long refundId = refund.getId();
        String who = actor.username() + " (" + actor.role() + ")";
        Long assessmentId = rv.getHoldAssessmentId();
        String riskReasons = null;
        String eventType = null;
        String summary = null;
        String message = null;

        switch (action) {
            case START -> {
                rv.setAssignedTo(actor.username());
                rv.setReviewStartedAt(now);
                eventType = "review.started";
                summary = "Review started by " + who + "." + (text != null ? " Note: " + text : "");
                message = "Review started. The case is now assigned to you.";
            }
            case REQUEST_INFO -> {
                rv.setInfoRequestedBy(actor.username());
                rv.setInfoRequestedAt(now);
                rv.setInfoRequestReason(text);
                rv.setAssignedTo(null);
                caseService.updateHighRiskReviewCase(refundId, trim("MORE INFORMATION REQUIRED (requested by " + who + "): " + text
                        + " - add the information and resubmit the review case. The refund stays ON HOLD until a reviewer decides.", 1000), null);
                eventType = "review.info_requested";
                summary = "More information requested by " + who + ": " + text;
                message = "Returned to operations for more information. The refund stays ON HOLD.";
            }
            case RESUBMIT -> {
                rv.setResubmissionCount(rv.getResubmissionCount() + 1);
                rv.setAssignedTo(null);
                rv.setReviewStartedAt(null);
                RiskAssessment a = riskService.assessAndPersist(refund, RiskScoringService.TRIGGER_MANUAL);
                assessmentId = a.getId();
                riskReasons = riskService.reasonsSummary(a);
                caseService.updateHighRiskReviewCase(refundId, "Resubmitted by " + who + " with the requested information. "
                        + "A REVIEWER or ADMIN must decide; approval only triggers a SIMULATED release.", null);
                eventType = "review.resubmitted";
                summary = "Resubmitted for review by " + who + ": " + text;
                message = "Resubmitted. The case is back in the reviewers' pending queue.";
            }
            case ESCALATE -> {
                rv.setEscalatedBy(actor.username());
                rv.setEscalatedAt(now);
                rv.setEscalationReason(text);
                rv.setAssignedTo(null);
                caseService.updateHighRiskReviewCase(refundId, trim("ESCALATED by " + who + ": " + text
                        + " - only an ADMIN may decide this case.", 1000), "CRITICAL");
                eventType = "review.escalated";
                summary = "Case escalated to ADMIN by " + who + ": " + text;
                message = "Escalated. Only an ADMIN can decide this case now.";
            }
            case NOTE -> {
                eventType = "review.note";
                summary = "Investigation note by " + who + ": " + text;
                message = "Note added.";
            }
            default -> throw new IllegalStateException("Unexpected review action " + action);
        }

        rv.setStatus(ReviewStateMachine.next(rv.getStatus(), action));
        rv.setLastActionAt(now);
        reviewRepository.save(rv);

        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("action", action.name());
        if (text != null) extra.put("text", text);
        extra.put("reviewStatus", rv.getStatus().name());
        audit(refundId, eventType, actor.username(), actor.role().name(), summary, riskReasons, assessmentId, rv.getId(), extra);
        log.info("Review case {} (refund {}): {} by {} -> {}", rv.getId(), refundId, action, who, rv.getStatus());
        return result(rv, refund, message);
    }

    /**
     * Who may do what. Returns null when {@code actor} may perform {@code action} on the case right now, otherwise
     * the error to raise. Used both to enforce actions and to tell the UI which buttons to show.
     */
    public ApiException denial(RefundReview rv, Refund refund, ReviewAction action, Actor actor) {
        Role role = actor.role();
        boolean decider = RolePermissions.has(role, Permission.REVIEW_DECIDE);
        boolean submitter = RolePermissions.has(role, Permission.REVIEW_SUBMIT);
        boolean escalationDecider = RolePermissions.has(role, Permission.REVIEW_ESCALATION_DECIDE);

        switch (action) {
            case RESUBMIT -> {
                if (!submitter) return ApiException.forbidden("Only an OPERATOR or ADMIN can resubmit a review case.");
            }
            case NOTE -> {
                if (!decider && !submitter) return ApiException.forbidden("You are not allowed to add notes to review cases.");
            }
            default -> {
                if (!decider) return ApiException.forbidden("Only a REVIEWER or ADMIN can " + label(action) + ".");
            }
        }
        if (!ReviewStateMachine.allows(rv.getStatus(), action)) {
            return ApiException.conflict("Review case #" + rv.getId() + " is " + rv.getStatus() + "; you cannot "
                    + label(action) + " in that state.");
        }
        if (refund.getDerivedStatus() != RefundStatus.ON_HOLD) {
            return ApiException.conflict("Refund " + refund.getId() + " is no longer on hold (" + refund.getDerivedStatus() + ").");
        }
        if (action == ReviewAction.NOTE || action == ReviewAction.RESUBMIT) return null;

        if (rv.getStatus() == ReviewStatus.ESCALATED && !escalationDecider) {
            return ApiException.forbidden("This case was escalated; only an ADMIN can act on it.");
        }
        if (rv.getStatus() == ReviewStatus.UNDER_REVIEW && rv.getAssignedTo() != null
                && !rv.getAssignedTo().equalsIgnoreCase(actor.username()) && !escalationDecider) {
            return ApiException.conflict("Review case #" + rv.getId() + " is under review by " + rv.getAssignedTo() + ".");
        }
        if ((action == ReviewAction.APPROVE || action == ReviewAction.REJECT)
                && RefundReview.ORIGIN_MANUAL.equals(rv.getOrigin()) && rv.getSubmittedBy() != null
                && rv.getSubmittedBy().equalsIgnoreCase(actor.username())) {
            return ApiException.forbidden("Four-eyes rule: you submitted this case, so a different reviewer must decide it.");
        }
        return null;
    }

    /** Actions the actor may perform on the case right now (for the UI; the server re-checks on every call). */
    public List<String> allowedActions(RefundReview rv, Refund refund, Actor actor) {
        List<String> out = new ArrayList<>();
        if (rv == null || refund == null || actor == null) return out;
        for (ReviewAction a : ReviewAction.values()) {
            if (denial(rv, refund, a, actor) == null) out.add(a.name());
        }
        return out;
    }

    /** Whether the actor could submit this refund for review right now. */
    public boolean canSubmit(Refund refund, Actor actor) {
        return refund != null && actor != null
                && RolePermissions.has(actor.role(), Permission.REVIEW_SUBMIT)
                && ReviewStateMachine.canSubmit(refund.getDerivedStatus())
                && !reviewRepository.existsByRefundIdAndStatusIn(refund.getId(), ReviewStateMachine.openStatuses());
    }

    /**
     * Manual reassessment. Stores a fresh assessment; if the refund is still holdable, has never been
     * reviewed and now meets the hold criteria, it is held. A refund already ON_HOLD stays pending until a
     * human decides - a lower new score never releases it automatically.
     */
    @Transactional
    public Map<String, Object> reassess(Long refundId, Actor actor) {
        Refund refund = lock(refundId); // serialises concurrent reassessments of the same refund
        Optional<RiskAssessment> before = riskService.latest(refundId);
        RiskScoringService.Outcome outcome = riskService.assess(refund, RiskScoringService.TRIGGER_MANUAL);
        RiskAssessment a = outcome.assessment();
        boolean heldNow = evaluateAndHold(refund, RiskScoringService.TRIGGER_INGESTION).isPresent();

        if (!outcome.reused()) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("summary", "Risk reassessed by " + actor.username() + " (" + actor.role() + "): " + a.getScore() + "/100 " + a.getLevel()
                    + before.map(b -> " (was " + b.getScore() + "/100 " + b.getLevel() + ")").orElse(" (first assessment)") + "."
                    + (heldNow ? " Refund placed ON HOLD for review." : ""));
            p.put("actor", actor.username());
            p.put("actorRole", actor.role().name());
            p.put("riskAssessmentId", a.getId());
            p.put("score", a.getScore());
            p.put("level", a.getLevel().name());
            p.put("ruleVersion", a.getEngineVersion());
            before.ifPresent(b -> { p.put("previousScore", b.getScore()); p.put("previousLevel", b.getLevel().name()); });
            riskService.audit(refundId, "risk.reassessed", p);
        }

        Map<String, Object> out = new LinkedHashMap<>(riskService.view(a, new RiskScoringService.Staleness(false, null), refund));
        out.put("reused", outcome.reused());
        out.put("heldNow", heldNow);
        out.put("refundStatus", refund.getDerivedStatus());
        out.put("message", outcome.reused()
                ? "Inputs unchanged since the assessment a moment ago; the existing assessment was kept (no duplicate)."
                : "Risk reassessed and stored.");
        return out;
    }

    /** A bank settlement arrived for a refund that was still on hold: close the open review without a human decision. */
    @Transactional
    public void supersedeOnSettlement(Refund refund, SettlementRow row) {
        Optional<RefundReview> open = reviewRepository.findFirstByRefundIdAndStatusIn(refund.getId(), ReviewStateMachine.openStatuses());
        if (open.isEmpty()) return;
        RefundReview rv = open.get();
        rv.setStatus(ReviewStatus.SUPERSEDED);
        rv.setDecidedBy("SYSTEM");
        rv.setDecisionReason("Bank settlement (UTR " + row.getUtrNumber() + ") arrived while the refund was on hold.");
        rv.setDecidedAt(LocalDateTime.now());
        rv.setLastActionAt(rv.getDecidedAt());
        reviewRepository.save(rv);
        caseService.resolveHighRiskReviewCase(refund.getId(), "[AUTO] Review superseded: bank settlement received while on hold.");
        audit(refund.getId(), "review.superseded", "SYSTEM", null,
                "Review closed automatically: the bank settled this refund (UTR " + row.getUtrNumber() + ") while it was on hold.",
                null, rv.getHoldAssessmentId(), rv.getId(), null);
        caseService.openForRefund(refund, "SETTLED_WHILE_ON_HOLD", "HIGH", row);
    }

    /** Review cases waiting for a reviewer/admin (pending, under review, escalated). */
    public long pendingCount() {
        return reviewRepository.countByStatusIn(ReviewStateMachine.awaitingReviewerStatuses());
    }

    // ── helpers ──

    private Refund lock(Long refundId) {
        return refundRepository.findByIdForUpdate(refundId)
                .orElseThrow(() -> ApiException.notFound("Refund " + refundId + " not found."));
    }

    private static String requireReason(String reason) {
        String err = ReviewStateMachine.validateReason(reason);
        if (err != null) throw ApiException.badRequest(err);
        return reason.trim();
    }

    private static String label(ReviewAction a) {
        return switch (a) {
            case START -> "start a review";
            case REQUEST_INFO -> "request more information";
            case RESUBMIT -> "resubmit the case";
            case ESCALATE -> "escalate the case";
            case APPROVE -> "approve the release";
            case REJECT -> "reject the release";
            case NOTE -> "add a note";
        };
    }

    private static Map<String, Object> result(RefundReview rv, Refund refund, String message) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("reviewId", rv.getId());
        out.put("refundId", rv.getRefundId());
        out.put("status", rv.getStatus());
        out.put("refundStatus", refund.getDerivedStatus());
        out.put("assignedTo", rv.getAssignedTo());
        out.put("message", message);
        return out;
    }

    // ── audit (rg_refund_events, the refund's audit ledger) ──
    private void audit(Long refundId, String eventType, String actor, String role, String summary,
                       String riskReasons, Long assessmentId, Long reviewId, Map<String, Object> extra) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("summary", summary);
        p.put("actor", actor);
        if (role != null) p.put("actorRole", role);
        if (riskReasons != null) p.put("riskReasons", riskReasons);
        p.put("riskAssessmentId", assessmentId);
        p.put("reviewId", reviewId);
        if (extra != null) extra.forEach(p::putIfAbsent);
        String json;
        try {
            json = mapper.writeValueAsString(p);
        } catch (JsonProcessingException e) {
            json = "{\"summary\":\"" + eventType + "\"}";
        }
        RefundEvent ev = new RefundEvent();
        ev.setRefundId(refundId);
        ev.setSource(SOURCE);
        ev.setExternalEventId("REVIEW-" + UUID.randomUUID());
        ev.setEventType(eventType);
        ev.setRawPayload(json);
        ev.setEventTime(LocalDateTime.now());
        ev.setReceivedAt(LocalDateTime.now());
        eventRepository.save(ev);
    }

    private static String trim(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }
}
