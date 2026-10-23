package com.refundguard.sla;

import com.refundguard.model.Case;
import com.refundguard.model.Refund;
import com.refundguard.model.RefundStatus;
import com.refundguard.repository.RefundRepository;
import com.refundguard.service.CaseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RBI TAT-aware SLA engine.
 *
 * Old behaviour: every GATEWAY_PROCESSED refund that was not in the latest bank
 * file was flagged POTENTIAL_DELAY immediately, regardless of time or method.
 *
 * New behaviour, per unsettled refund:
 *   - deadline = end of day (T + TAT days for its payment method)
 *   - due today/tomorrow  -> POTENTIAL_DELAY (early warning, no case)
 *   - past the deadline   -> SLA_BREACHED + HIGH "SLA_BREACH" case with
 *                            days overdue and estimated compensation
 *   - otherwise           -> untouched (no false alarm)
 */
@Service
public class SlaService {

    private static final Logger log = LoggerFactory.getLogger(SlaService.class);

    private final SlaPolicyProperties policy;
    private final RefundRepository refundRepository;
    private final CaseService caseService;

    public SlaService(SlaPolicyProperties policy, RefundRepository refundRepository, CaseService caseService) {
        this.policy = policy;
        this.refundRepository = refundRepository;
        this.caseService = caseService;
    }

    /** Stamps the TAT deadline and compensation rate onto a refund (call when it is created). */
    public void applyPolicy(Refund refund) {
        if (refund.getRequestedAt() == null) refund.setRequestedAt(LocalDateTime.now());
        int tat = policy.tatDaysFor(refund.getPaymentMethod());
        refund.setSlaTatDays(tat);
        refund.setSlaDueAt(SlaCalculator.dueAt(refund.getRequestedAt(), tat));
        refund.setCompensationPerDay(policy.compensationPerDayFor(refund.getPaymentMethod()));
    }

    @Scheduled(initialDelayString = "${refundguard.sla.initial-delay-ms:60000}",
               fixedDelayString = "${refundguard.sla.check-interval-ms:300000}")
    public void scheduledCheck() {
        Map<String, Object> r = evaluateAll();
        log.info("Scheduled SLA check: {}", r);
    }

    @Transactional
    public Map<String, Object> evaluateAll() {
        LocalDateTime now = LocalDateTime.now();
        List<Refund> unsettled = refundRepository.findBySettledAtIsNull();
        int onTrack = 0, dueSoon = 0, breached = 0, newlyBreached = 0;
        BigDecimal exposure = BigDecimal.ZERO;

        for (Refund r : unsettled) {
            if (r.getDerivedStatus() == RefundStatus.RESOLVED || r.getDerivedStatus() == RefundStatus.REJECTED) continue;
            if (r.getSlaDueAt() == null) applyPolicy(r); // refunds created before the SLA engine existed

            String state = SlaCalculator.state(r.getSlaDueAt(), null, now);
            RefundStatus st = r.getDerivedStatus();

            if (SlaCalculator.BREACHED.equals(state)) {
                breached++;
                exposure = exposure.add(r.getCompensationDue());
                // FAILED_REPORTED keeps its status (it already says what went wrong); the SLA case is still opened.
                // ON_HOLD keeps its status (only the review workflow may change it); the TAT clock and SLA case still run.
                if (st != RefundStatus.SLA_BREACHED && st != RefundStatus.FAILED_REPORTED && st != RefundStatus.ON_HOLD) {
                    r.setDerivedStatus(RefundStatus.SLA_BREACHED);
                    newlyBreached++;
                }
                refundRepository.save(r);
                Case c = caseService.openForRefund(r, "SLA_BREACH", "HIGH", null);
                caseService.refreshSlaCase(c, r);
            } else if (SlaCalculator.DUE_SOON.equals(state)) {
                dueSoon++;
                if (st == RefundStatus.REQUESTED || st == RefundStatus.GATEWAY_PENDING || st == RefundStatus.GATEWAY_PROCESSED) {
                    r.setDerivedStatus(RefundStatus.POTENTIAL_DELAY);
                }
                refundRepository.save(r);
            } else {
                onTrack++;
                refundRepository.save(r);
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("checkedAt", now);
        out.put("unsettledRefunds", unsettled.size());
        out.put("onTrack", onTrack);
        out.put("dueSoon", dueSoon);
        out.put("breached", breached);
        out.put("newlyBreached", newlyBreached);
        out.put("compensationExposure", exposure);
        out.put("message", "RBI TAT check complete: " + breached + " breached (" + newlyBreached + " new), "
                + dueSoon + " due within a day, " + onTrack + " on track. Estimated compensation exposure ₹" + exposure.toPlainString() + ".");
        return out;
    }

    public Map<String, Map<String, Object>> describePolicy() {
        return policy.describe();
    }
}
