package com.refundguard.service;

import com.refundguard.model.*;
import com.refundguard.repository.*;
import com.refundguard.review.RefundReviewService;
import com.refundguard.risk.RiskScoringService;
import com.refundguard.sla.SlaService;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    /** Case types that disappear once the bank confirms the money reached the customer. */
    private static final List<String> CLEARED_BY_SETTLEMENT = List.of("SLA_BREACH", "MISSING_SETTLEMENT", "REFUND_FAILED");

    private final RefundRepository refundRepository;
    private final SettlementRowRepository settlementRowRepository;
    private final RefundEventRepository refundEventRepository;
    private final CaseService caseService;
    private final SlaService slaService;
    private final RefundReviewService reviewService;
    private final RiskScoringService riskService;

    public ReconciliationService(RefundRepository refundRepository, SettlementRowRepository settlementRowRepository,
                                 RefundEventRepository refundEventRepository, CaseService caseService, SlaService slaService,
                                 RefundReviewService reviewService, RiskScoringService riskService) {
        this.refundRepository = refundRepository;
        this.settlementRowRepository = settlementRowRepository;
        this.refundEventRepository = refundEventRepository;
        this.caseService = caseService;
        this.slaService = slaService;
        this.reviewService = reviewService;
        this.riskService = riskService;
    }

    @Transactional
    public Map<String, Object> uploadSettlementCsv(MultipartFile file) throws Exception {
        String fileId = "BATCH_" + System.currentTimeMillis();
        List<SettlementRow> rows = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8));
             CSVParser csvParser = new CSVParser(reader, CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).setIgnoreHeaderCase(true).setTrim(true).build())) {

            for (CSVRecord record : csvParser) {
                String ref = record.get("refund_reference");
                String utr = record.get("utr_number");
                BigDecimal amount = new BigDecimal(record.get("amount"));
                String dateStr = record.get("settlement_date");
                LocalDate date = LocalDate.parse(dateStr);

                SettlementRow row = new SettlementRow();
                row.setFileId(fileId);
                row.setRefundReference(ref);
                row.setUtrNumber(utr);
                row.setAmount(amount);
                row.setCurrency("INR");
                row.setSettlementDate(date);
                row.setIsMatched(false);
                rows.add(row);
            }
        }

        settlementRowRepository.saveAll(rows);
        log.info("Uploaded settlement CSV fileId={}, rowsCount={}", fileId, rows.size());
        return runReconciliation(fileId);
    }

    @Transactional
    public Map<String, Object> runReconciliation(String fileId) {
        List<SettlementRow> rowsToProcess = (fileId != null)
                ? settlementRowRepository.findByFileId(fileId)
                : settlementRowRepository.findByIsMatchedFalse();

        int matchedCount = 0;
        int mismatchCount = 0;
        int unmatchedCount = 0;
        int duplicateCount = 0;
        int lateCount = 0;
        int rejectedSettledCount = 0;
        Map<Long, Refund> touched = new LinkedHashMap<>(); // refunds whose settlement data changed -> re-score

        for (SettlementRow sRow : rowsToProcess) {
            String ref = sRow.getRefundReference();
            Optional<Refund> refundOpt = refundRepository.findByProviderRefundId(ref);
            if (refundOpt.isEmpty()) {
                refundOpt = refundRepository.findByOrderId(ref);
            }

            if (refundOpt.isEmpty()) {
                // Gap fix: previously these rows were silently ignored.
                caseService.openForUnmatchedSettlement(sRow);
                unmatchedCount++;
                continue;
            }

            Refund refund = refundOpt.get();
            touched.put(refund.getId(), refund);
            sRow.setIsMatched(true);
            settlementRowRepository.save(sRow);

            // Money moved for a refund a reviewer rejected: never overwrite REJECTED, raise a CRITICAL case instead.
            if (refund.getDerivedStatus() == RefundStatus.REJECTED) {
                caseService.openForRefund(refund, "REJECTED_REFUND_SETTLED", "CRITICAL", sRow);
                rejectedSettledCount++;
                continue;
            }
            // Bank settled a refund that is still on hold: close the pending review (audited) and flag it.
            if (refund.getDerivedStatus() == RefundStatus.ON_HOLD) {
                reviewService.supersedeOnSettlement(refund, sRow);
            }
            caseService.autoResolveForSettlementRow(sRow.getId(), "Matched to refund #" + refund.getId() + " on a later reconciliation run.");

            // Second credit for a refund that is already settled -> possible double refund.
            if (refund.getSettledAt() != null && refund.getBankUtr() != null && !refund.getBankUtr().equals(sRow.getUtrNumber())) {
                caseService.openForRefund(refund, "DUPLICATE_SETTLEMENT", "HIGH", sRow);
                duplicateCount++;
                continue;
            }

            // The bank's settlement date is when the customer actually got the money.
            LocalDateTime settledAt = sRow.getSettlementDate() != null ? sRow.getSettlementDate().atStartOfDay() : LocalDateTime.now();
            if (refund.getSlaDueAt() == null) slaService.applyPolicy(refund);
            refund.setSettledAt(settledAt);
            refund.setSettledAmount(sRow.getAmount());
            refund.setBankUtr(sRow.getUtrNumber());
            refund.setLastEventAt(LocalDateTime.now());
            boolean late = refund.getDaysOverdue() > 0;
            if (late) lateCount++;

            if (refund.getAmount().compareTo(sRow.getAmount()) == 0) {
                refund.setDerivedStatus(RefundStatus.SETTLEMENT_MATCHED);
                refundRepository.save(refund);

                RefundEvent event = new RefundEvent();
                event.setRefundId(refund.getId());
                event.setSource("BANK_SETTLEMENT");
                event.setExternalEventId("STMT_" + sRow.getId());
                event.setEventType("settlement.matched");
                event.setRawPayload("{\"utr\":\"" + sRow.getUtrNumber() + "\", \"settledAmount\":" + sRow.getAmount()
                        + ", \"settlementDate\":\"" + sRow.getSettlementDate() + "\", \"daysLate\":" + refund.getDaysOverdue() + "}");
                event.setEventTime(LocalDateTime.now());
                event.setReceivedAt(LocalDateTime.now());
                refundEventRepository.save(event);

                matchedCount++;
            } else {
                refund.setDerivedStatus(RefundStatus.AMOUNT_MISMATCH);
                refundRepository.save(refund);
                caseService.openForRefund(refund, "AMOUNT_MISMATCH", "HIGH", sRow);
                mismatchCount++;
            }

            String note = "Bank settlement received on " + sRow.getSettlementDate() + " (UTR " + sRow.getUtrNumber() + ")"
                    + (late ? ", " + refund.getDaysOverdue() + " day(s) after the RBI TAT deadline; final compensation estimate ₹"
                              + refund.getCompensationDue().toPlainString() + "." : ", within the RBI TAT deadline.");
            caseService.autoResolveForRefund(refund.getId(), CLEARED_BY_SETTLEMENT, note);
        }

        // Settlement amount / UTRs / discrepancy cases are risk inputs: re-score (identical inputs reuse the latest assessment).
        for (Refund r : touched.values()) riskService.assessAndPersist(r, RiskScoringService.TRIGGER_SETTLEMENT);

        Map<String, Object> sla = slaService.evaluateAll();

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("fileId", fileId);
        summary.put("processedRows", rowsToProcess.size());
        summary.put("matchedCount", matchedCount);
        summary.put("mismatchCount", mismatchCount);
        summary.put("unmatchedSettlementCount", unmatchedCount);
        summary.put("duplicateSettlementCount", duplicateCount);
        summary.put("settledAfterDeadlineCount", lateCount);
        summary.put("rejectedRefundSettledCount", rejectedSettledCount);
        summary.put("sla", sla);
        summary.put("message", "Reconciliation complete: " + matchedCount + " matched, " + mismatchCount + " amount mismatch, "
                + unmatchedCount + " unknown bank credit(s), " + duplicateCount + " duplicate credit(s). " + sla.get("message"));
        return summary;
    }

    /** Kept for API compatibility; now delegates to the time-aware SLA engine. */
    public Map<String, Object> checkOverdueRefunds() {
        return slaService.evaluateAll();
    }
}
