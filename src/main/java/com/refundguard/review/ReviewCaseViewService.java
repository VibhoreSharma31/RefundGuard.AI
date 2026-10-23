package com.refundguard.review;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.refundguard.exception.ApiException;
import com.refundguard.model.*;
import com.refundguard.repository.*;
import com.refundguard.risk.RiskLevel;
import com.refundguard.risk.RiskScoringService;
import com.refundguard.security.Actor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;

/**
 * Read side of the review-case workflow: queue items, dashboard counts and the full case context.
 * Everything is assembled from the persisted records (refund, order, risk assessments, cases, settlement rows,
 * audit ledger); nothing is copied into the review row or invented.
 */
@Service
@Transactional(readOnly = true)
public class ReviewCaseViewService {

    private final RefundReviewRepository reviewRepository;
    private final RefundRepository refundRepository;
    private final OrderRepository orderRepository;
    private final CaseRepository caseRepository;
    private final SettlementRowRepository settlementRowRepository;
    private final RefundEventRepository eventRepository;
    private final RiskAssessmentRepository assessmentRepository;
    private final RiskScoringService riskService;
    private final RefundReviewService reviewService;
    private final ObjectMapper mapper;

    public ReviewCaseViewService(RefundReviewRepository reviewRepository, RefundRepository refundRepository,
                                 OrderRepository orderRepository, CaseRepository caseRepository,
                                 SettlementRowRepository settlementRowRepository, RefundEventRepository eventRepository,
                                 RiskAssessmentRepository assessmentRepository, RiskScoringService riskService,
                                 RefundReviewService reviewService, ObjectMapper mapper) {
        this.reviewRepository = reviewRepository;
        this.refundRepository = refundRepository;
        this.orderRepository = orderRepository;
        this.caseRepository = caseRepository;
        this.settlementRowRepository = settlementRowRepository;
        this.eventRepository = eventRepository;
        this.assessmentRepository = assessmentRepository;
        this.riskService = riskService;
        this.reviewService = reviewService;
        this.mapper = mapper;
    }

    /** Review queue. {@code status}: PENDING (default), OPEN (every active case), ALL (latest 100) or a ReviewStatus. */
    public List<Map<String, Object>> queue(String status, Actor actor) {
        List<RefundReview> list = load(status);
        Set<Long> ids = new HashSet<>();
        list.forEach(r -> ids.add(r.getRefundId()));
        Map<Long, Refund> refunds = new HashMap<>();
        refundRepository.findAllById(ids).forEach(r -> refunds.put(r.getId(), r));
        Map<Long, RiskAssessment> latest = riskService.latestFor(ids);
        Map<Long, List<String>> openIssues = openIssueTypes(ids);
        Map<Long, List<Map<String, Object>>> factors = riskService.factorsFor(latest.values()); // one query, no N+1

        List<Map<String, Object>> out = new ArrayList<>();
        for (RefundReview rv : list) {
            Refund rf = refunds.get(rv.getRefundId());
            Map<String, Object> m = reviewMap(rv);
            if (rf != null) {
                m.put("refund", refundSummary(rf));
                List<String> allowed = reviewService.allowedActions(rv, rf, actor);
                m.put("allowedActions", allowed);
                m.put("canDecide", allowed.contains(ReviewAction.APPROVE.name()));
                m.put("slaBreached", "BREACHED".equals(rf.getSlaState()));
            } else {
                m.put("allowedActions", List.of());
                m.put("canDecide", false);
                m.put("slaBreached", false);
            }
            RiskAssessment a = latest.get(rv.getRefundId());
            if (a != null) m.put("risk", riskService.view(a, riskService.staleness(a, rf, false),
                    rf == null ? null : String.valueOf(rf.getDerivedStatus()), factors.get(a.getId())));
            m.put("highRisk", a != null && (a.getLevel() == RiskLevel.HIGH || a.getLevel() == RiskLevel.CRITICAL));
            m.put("openIssues", openIssues.getOrDefault(rv.getRefundId(), List.of()));
            out.add(m);
        }
        return out;
    }

    /** Dashboard counts, all computed from persisted review cases. */
    public Map<String, Object> summary(Actor actor) {
        List<RefundReview> open = reviewRepository.findByStatusInOrderByHeldAtAsc(ReviewStateMachine.openStatuses());
        Set<Long> ids = new HashSet<>();
        open.forEach(r -> ids.add(r.getRefundId()));
        Map<Long, Refund> refunds = new HashMap<>();
        refundRepository.findAllById(ids).forEach(r -> refunds.put(r.getId(), r));
        Map<Long, RiskAssessment> latest = riskService.latestFor(ids);

        long highRisk = 0, slaBreached = 0, mine = 0;
        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (ReviewStatus s : ReviewStatus.values()) byStatus.put(s.name(), 0L);
        for (RefundReview rv : open) {
            byStatus.merge(rv.getStatus().name(), 1L, Long::sum);
            RiskAssessment a = latest.get(rv.getRefundId());
            if (a != null && (a.getLevel() == RiskLevel.HIGH || a.getLevel() == RiskLevel.CRITICAL)) highRisk++;
            Refund rf = refunds.get(rv.getRefundId());
            if (rf != null && "BREACHED".equals(rf.getSlaState())) slaBreached++;
            if (rv.getStatus() == ReviewStatus.UNDER_REVIEW && actor.username().equalsIgnoreCase(String.valueOf(rv.getAssignedTo()))) mine++;
        }
        long approved = reviewRepository.countByStatus(ReviewStatus.APPROVED);
        long rejected = reviewRepository.countByStatus(ReviewStatus.REJECTED);
        byStatus.put(ReviewStatus.APPROVED.name(), approved);
        byStatus.put(ReviewStatus.REJECTED.name(), rejected);
        byStatus.put(ReviewStatus.SUPERSEDED.name(), reviewRepository.countByStatus(ReviewStatus.SUPERSEDED));

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("pending", byStatus.get(ReviewStatus.PENDING.name()));
        m.put("underReview", byStatus.get(ReviewStatus.UNDER_REVIEW.name()));
        m.put("moreInfoRequired", byStatus.get(ReviewStatus.MORE_INFO_REQUIRED.name()));
        m.put("escalated", byStatus.get(ReviewStatus.ESCALATED.name()));
        m.put("awaitingReviewer", reviewService.pendingCount());
        m.put("open", (long) open.size());
        m.put("highRisk", highRisk);
        m.put("slaBreached", slaBreached);
        m.put("completed", approved + rejected);
        m.put("approved", approved);
        m.put("rejected", rejected);
        m.put("superseded", byStatus.get(ReviewStatus.SUPERSEDED.name()));
        m.put("myInProgress", mine);
        m.put("byStatus", byStatus);
        return m;
    }

    /** Everything a reviewer needs to decide one case, from the source records. */
    public Map<String, Object> detail(Long reviewId, Actor actor) {
        RefundReview rv = reviewRepository.findById(reviewId)
                .orElseThrow(() -> ApiException.notFound("Review case " + reviewId + " not found."));
        Refund refund = refundRepository.findById(rv.getRefundId())
                .orElseThrow(() -> ApiException.notFound("Refund " + rv.getRefundId() + " for review case " + reviewId + " not found."));
        Long refundId = refund.getId();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("review", reviewMap(rv));
        out.put("refund", refund);
        out.put("order", orderRepository.findByExternalOrderId(refund.getOrderId()).orElse(null));

        // Risk: current (with full staleness check), the assessment that caused the hold, and recent history.
        Map<String, Object> risk = new LinkedHashMap<>();
        riskService.latest(refundId).ifPresent(a -> risk.put("current", riskService.view(a, riskService.staleness(a, refund, true), refund)));
        if (rv.getHoldAssessmentId() != null) {
            assessmentRepository.findById(rv.getHoldAssessmentId())
                    .ifPresent(a -> risk.put("atSubmission", riskService.view(a, riskService.staleness(a, refund, false))));
        }
        List<Map<String, Object>> hist = new ArrayList<>();
        for (RiskAssessment a : riskService.history(refundId)) {
            Map<String, Object> h = new LinkedHashMap<>();
            h.put("assessmentId", a.getId());
            h.put("score", a.getScore());
            h.put("level", a.getLevel());
            h.put("assessedAt", a.getAssessedAt());
            h.put("trigger", a.getTrigger());
            hist.add(h);
        }
        risk.put("history", hist);
        out.put("risk", risk);

        // Related discrepancy cases (mismatch, duplicate settlement, SLA breach, failure ...).
        List<Case> cases = new ArrayList<>(caseRepository.findByRefundId(refundId));
        cases.sort(Comparator.comparing(Case::getId).reversed());
        List<Map<String, Object>> caseViews = new ArrayList<>();
        Set<Long> settlementRowIds = new HashSet<>();
        for (Case c : cases) {
            Map<String, Object> cv = new LinkedHashMap<>();
            cv.put("id", c.getId());
            cv.put("issueType", c.getIssueType());
            cv.put("severity", c.getSeverity());
            cv.put("status", c.getStatus());
            cv.put("hypothesis", c.getAiRootCauseHypothesis());
            cv.put("recommendedAction", c.getRecommendedAction());
            cv.put("resolutionNotes", c.getResolutionNotes());
            cv.put("createdAt", c.getCreatedAt());
            cv.put("resolvedAt", c.getResolvedAt());
            caseViews.add(cv);
            if (c.getSettlementRowId() != null) settlementRowIds.add(c.getSettlementRowId());
        }
        out.put("relatedCases", caseViews);

        // Settlement rows linked through cases or the refund reference (order id / gateway refund id).
        Map<Long, SettlementRow> rows = new LinkedHashMap<>();
        List<String> refs = new ArrayList<>();
        if (refund.getOrderId() != null) refs.add(refund.getOrderId());
        if (refund.getProviderRefundId() != null) refs.add(refund.getProviderRefundId());
        if (!refs.isEmpty()) settlementRowRepository.findByRefundReferenceIn(refs).forEach(r -> rows.put(r.getId(), r));
        if (!settlementRowIds.isEmpty()) settlementRowRepository.findAllById(settlementRowIds).forEach(r -> rows.put(r.getId(), r));
        out.put("settlements", new ArrayList<>(rows.values()));

        out.put("indicators", indicators(refund, cases, rows.values()));

        // Audit ledger of the refund, plus the review-workflow entries of THIS case as its notes/decision history.
        List<RefundEvent> events = eventRepository.findByRefundIdOrderByEventTimeAsc(refundId);
        out.put("timeline", events);
        out.put("activity", activity(events, rv.getId()));

        List<Map<String, Object>> previous = new ArrayList<>();
        for (RefundReview other : reviewRepository.findByRefundIdOrderByHeldAtDesc(refundId)) {
            if (!other.getId().equals(rv.getId())) previous.add(reviewMap(other));
        }
        out.put("previousReviews", previous);

        out.put("allowedActions", reviewService.allowedActions(rv, refund, actor));
        out.put("releaseMode", "SIMULATED");
        out.put("releaseNotice", "No real payment system is integrated. Approval returns the refund to normal processing; "
                + "RefundGuard never initiates or moves money.");
        return out;
    }

    /** The open review case of a refund and what the caller may do (used by the refund detail panel). */
    public Map<String, Object> refundReviewState(Refund refund, Actor actor) {
        Map<String, Object> m = new LinkedHashMap<>();
        Optional<RefundReview> open = reviewRepository.findFirstByRefundIdAndStatusIn(refund.getId(), ReviewStateMachine.openStatuses());
        m.put("openReviewId", open.map(RefundReview::getId).orElse(null));
        m.put("openReviewStatus", open.map(RefundReview::getStatus).orElse(null));
        m.put("allowedActions", open.map(rv -> reviewService.allowedActions(rv, refund, actor)).orElse(List.of()));
        m.put("canSubmit", reviewService.canSubmit(refund, actor));
        return m;
    }

    public static Map<String, Object> reviewMap(RefundReview rv) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("reviewId", rv.getId());
        m.put("refundId", rv.getRefundId());
        m.put("status", rv.getStatus());
        m.put("open", ReviewStateMachine.isOpen(rv.getStatus()));
        m.put("origin", rv.getOrigin());
        m.put("submittedBy", rv.getSubmittedBy() != null ? rv.getSubmittedBy() : "SYSTEM");
        m.put("submittedByRole", rv.getSubmittedByRole());
        m.put("submissionReason", rv.getSubmissionReason());
        m.put("heldAt", rv.getHeldAt());
        m.put("holdReason", rv.getHoldReason());
        m.put("holdAssessmentId", rv.getHoldAssessmentId());
        m.put("previousStatus", rv.getPreviousStatus());
        m.put("assignedTo", rv.getAssignedTo());
        m.put("reviewStartedAt", rv.getReviewStartedAt());
        m.put("infoRequestedBy", rv.getInfoRequestedBy());
        m.put("infoRequestedAt", rv.getInfoRequestedAt());
        m.put("infoRequestReason", rv.getInfoRequestReason());
        m.put("escalatedBy", rv.getEscalatedBy());
        m.put("escalatedAt", rv.getEscalatedAt());
        m.put("escalationReason", rv.getEscalationReason());
        m.put("resubmissionCount", rv.getResubmissionCount());
        m.put("lastActionAt", rv.getLastActionAt());
        m.put("decision", rv.getDecision());
        m.put("decidedBy", rv.getDecidedBy());
        m.put("decidedByRole", rv.getDecidedByRole());
        m.put("decisionReason", rv.getDecisionReason());
        m.put("decidedAt", rv.getDecidedAt());
        m.put("decisionAssessmentId", rv.getDecisionAssessmentId());
        m.put("simulatedRelease", rv.getSimulatedRelease());
        return m;
    }

    public static Map<String, Object> refundSummary(Refund r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("orderId", r.getOrderId());
        m.put("amount", r.getAmount());
        m.put("currency", r.getCurrency());
        m.put("status", r.getDerivedStatus());
        m.put("paymentMethod", r.getPaymentMethod());
        m.put("slaState", r.getSlaState());
        m.put("slaLabel", r.getSlaLabel());
        m.put("slaDueAt", r.getSlaDueAt());
        m.put("slaTatDays", r.getSlaTatDays());
        m.put("compensationDue", r.getCompensationDue());
        return m;
    }

    // ── internals ──

    private List<RefundReview> load(String status) {
        String s = status == null ? "PENDING" : status.trim().toUpperCase(Locale.ROOT);
        if ("ALL".equals(s)) return reviewRepository.findTop100ByOrderByHeldAtDesc();
        if ("OPEN".equals(s)) return reviewRepository.findByStatusInOrderByHeldAtAsc(ReviewStateMachine.openStatuses());
        ReviewStatus st;
        try {
            st = ReviewStatus.valueOf(s);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("status must be OPEN, ALL or one of " + Arrays.toString(ReviewStatus.values()) + ".");
        }
        return reviewRepository.findByStatusOrderByHeldAtAsc(st);
    }

    private Map<Long, List<String>> openIssueTypes(Collection<Long> refundIds) {
        Map<Long, List<String>> out = new HashMap<>();
        if (refundIds.isEmpty()) return out;
        for (Case c : caseRepository.findByRefundIdIn(refundIds)) {
            if (!"OPEN".equals(c.getStatus()) || RefundReviewService.CASE_TYPE.equals(c.getIssueType())) continue;
            out.computeIfAbsent(c.getRefundId(), k -> new ArrayList<>()).add(c.getIssueType());
        }
        return out;
    }

    private static Map<String, Object> indicators(Refund refund, List<Case> cases, Collection<SettlementRow> rows) {
        Set<String> types = new HashSet<>();
        cases.forEach(c -> types.add(c.getIssueType()));
        BigDecimal settled = refund.getSettledAmount();
        boolean amountMismatch = refund.getDerivedStatus() == RefundStatus.AMOUNT_MISMATCH || types.contains("AMOUNT_MISMATCH")
                || (settled != null && refund.getAmount() != null && settled.compareTo(refund.getAmount()) != 0);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("amountMismatch", amountMismatch);
        m.put("duplicateSettlement", types.contains("DUPLICATE_SETTLEMENT") || rows.size() > 1);
        m.put("settlementRows", rows.size());
        m.put("refundFailed", types.contains("REFUND_FAILED") || refund.getDerivedStatus() == RefundStatus.FAILED_REPORTED);
        m.put("slaState", refund.getSlaState());
        m.put("slaBreached", "BREACHED".equals(refund.getSlaState()));
        m.put("daysOverdue", refund.getDaysOverdue());
        m.put("compensationDue", refund.getCompensationDue());
        m.put("openCaseTypes", cases.stream().filter(c -> "OPEN".equals(c.getStatus())).map(Case::getIssueType).distinct().toList());
        return m;
    }

    /** Review-workflow ledger entries of one case: submission, start, notes, info requests, escalation, decisions. */
    private List<Map<String, Object>> activity(List<RefundEvent> events, Long reviewId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (RefundEvent ev : events) {
            if (!RefundReviewService.SOURCE.equals(ev.getSource())) continue;
            Map<String, Object> p = parse(ev.getRawPayload());
            Object rid = p.get("reviewId");
            if (rid == null || !String.valueOf(rid).equals(String.valueOf(reviewId))) continue;
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("eventId", ev.getId());
            a.put("type", ev.getEventType());
            a.put("at", ev.getEventTime());
            a.put("actor", p.get("actor"));
            a.put("actorRole", p.get("actorRole"));
            a.put("action", p.get("action"));
            a.put("text", p.get("text"));
            a.put("summary", p.get("summary"));
            a.put("reviewStatus", p.get("reviewStatus"));
            out.add(a);
        }
        return out;
    }

    private Map<String, Object> parse(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return mapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }
}
