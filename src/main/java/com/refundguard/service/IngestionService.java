package com.refundguard.service;

import com.refundguard.model.*;
import com.refundguard.repository.*;
import com.refundguard.review.RefundReviewService;
import com.refundguard.risk.RiskScoringService;
import com.refundguard.sla.SlaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    /** Statuses decided by the bank side / operators; a late gateway webhook must not overwrite them. */
    private static final Set<RefundStatus> FINAL_STATUSES =
            EnumSet.of(RefundStatus.SETTLEMENT_MATCHED, RefundStatus.AMOUNT_MISMATCH, RefundStatus.RESOLVED,
                    RefundStatus.ON_HOLD, RefundStatus.REJECTED); // held/rejected refunds change only via the review workflow

    private final RefundRepository refundRepository;
    private final RefundEventRepository refundEventRepository;
    private final OrderRepository orderRepository;
    private final SlaService slaService;
    private final CaseService caseService;
    private final RefundReviewService reviewService;

    public IngestionService(RefundRepository refundRepository, RefundEventRepository refundEventRepository, OrderRepository orderRepository,
                            SlaService slaService, CaseService caseService, RefundReviewService reviewService) {
        this.refundRepository = refundRepository;
        this.refundEventRepository = refundEventRepository;
        this.orderRepository = orderRepository;
        this.slaService = slaService;
        this.caseService = caseService;
        this.reviewService = reviewService;
    }

    @Transactional
    public boolean ingestEvent(String source, String externalEventId, String eventType,
                               String orderId, String providerPaymentId, String providerRefundId,
                               BigDecimal amount, String currency, String paymentMethod, String rawPayload) {

        if (refundEventRepository.existsBySourceAndExternalEventId(source, externalEventId)) {
            log.warn("Duplicate webhook ignored: source={}, eventId={}", source, externalEventId);
            return false;
        }

        Optional<Refund> found = providerRefundId != null ? refundRepository.findByProviderRefundId(providerRefundId) : Optional.empty();
        if (found.isEmpty()) found = refundRepository.findByOrderId(orderId);

        final boolean newRefund = found.isEmpty();
        Refund refund = found.orElseGet(() -> {
                    Refund r = new Refund();
                    r.setOrderId(orderId);
                    r.setProviderPaymentId(providerPaymentId);
                    r.setProviderRefundId(providerRefundId);
                    r.setAmount(amount);
                    r.setCurrency(currency != null ? currency : "INR");
                    r.setPaymentMethod(paymentMethod != null ? paymentMethod : "UPI");
                    r.setDerivedStatus(RefundStatus.REQUESTED);
                    r.setRequestedAt(LocalDateTime.now());
                    slaService.applyPolicy(r); // start the RBI TAT clock
                    return r;
                });

        if (providerRefundId != null && refund.getProviderRefundId() == null) {
            refund.setProviderRefundId(providerRefundId);
        }
        if (providerPaymentId != null && refund.getProviderPaymentId() == null) {
            refund.setProviderPaymentId(providerPaymentId);
        }

        if (eventType != null && eventType.toLowerCase().startsWith("refund.")) {
            refund.setGatewayStage(eventType.toLowerCase());
        }

        boolean failed = false;
        if (!FINAL_STATUSES.contains(refund.getDerivedStatus())) {
            if ("refund.created".equalsIgnoreCase(eventType)) {
                refund.setDerivedStatus(RefundStatus.GATEWAY_PENDING);
            } else if ("refund.processed".equalsIgnoreCase(eventType)) {
                refund.setDerivedStatus(RefundStatus.GATEWAY_PROCESSED);
            } else if ("refund.failed".equalsIgnoreCase(eventType)) {
                refund.setDerivedStatus(RefundStatus.FAILED_REPORTED);
                failed = true;
            }
        } else {
            log.info("Refund {} already {}; '{}' recorded on timeline only", refund.getId(), refund.getDerivedStatus(), eventType);
        }

        refund.setLastEventAt(LocalDateTime.now());
        refund = refundRepository.save(refund);

        RefundEvent event = new RefundEvent();
        event.setRefundId(refund.getId());
        event.setSource(source);
        event.setExternalEventId(externalEventId);
        event.setEventType(eventType);
        event.setRawPayload(rawPayload);
        event.setEventTime(LocalDateTime.now());
        event.setReceivedAt(LocalDateTime.now());

        refundEventRepository.save(event);

        // Risk check + hold: on creation of the refund record and on the gateway's refund.created event.
        if (newRefund || "refund.created".equalsIgnoreCase(eventType)) {
            reviewService.evaluateAndHold(refund, RiskScoringService.TRIGGER_INGESTION);
        }

        // Gap fix: a failed refund means the customer has NOT been paid - this needs a human now.
        if (failed) {
            caseService.openForRefund(refund, "REFUND_FAILED", "CRITICAL", null);
        }

        log.info("Event ingested: source={}, type={}, refundId={}", source, eventType, refund.getId());
        return true;
    }
}
