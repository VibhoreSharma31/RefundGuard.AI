package com.refundguard.ai;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.refundguard.model.Case;
import com.refundguard.model.Refund;
import com.refundguard.model.RefundEvent;
import com.refundguard.model.SettlementRow;
import com.refundguard.repository.CaseRepository;
import com.refundguard.repository.RefundEventRepository;
import com.refundguard.repository.RefundRepository;
import com.refundguard.repository.SettlementRowRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Real AI root-cause analysis for discrepancy cases using Claude.
 *
 * - Runs on demand (POST /api/cases/{id}/analyze) so cost is controlled by the operator.
 * - Only operational facts are sent (amounts, statuses, method, UTR, timeline, SLA);
 *   customer name / email are never sent to the model.
 * - If no ANTHROPIC_API_KEY is set, or the call fails / is refused, the case keeps the
 *   deterministic rule-based analysis, so the feature degrades gracefully.
 */
@Service
public class AiRootCauseService {

    private static final Logger log = LoggerFactory.getLogger(AiRootCauseService.class);

    private static final String SYSTEM_PROMPT = """
            You are a senior payments-reconciliation analyst for an Indian online merchant.
            You investigate refund discrepancy cases using the facts provided (refund record, gateway webhook timeline,
            bank settlement rows, and the RBI turnaround-time (TAT) SLA status).

            Reason only from the facts given. If something cannot be determined, say what extra data is needed instead of guessing.
            Amounts are in INR. UTR = bank transaction reference; ARN/RRN = card/UPI tracing reference.

            Reply with ONLY a JSON object (no markdown fences) with exactly these string fields:
              "hypothesis": the most likely root cause in at most 3 sentences, citing the specific facts (amounts, dates, statuses) that support it;
              "recommendedAction": numbered, concrete next steps for the operations team (at most 4 steps);
              "customerMessage": a short, polite status update the merchant can send to the customer - plain language, no internal jargon,
                                 and no promised dates that the facts do not support.
            """;

    private final CaseRepository caseRepository;
    private final RefundRepository refundRepository;
    private final RefundEventRepository refundEventRepository;
    private final SettlementRowRepository settlementRowRepository;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String model;
    private volatile AnthropicClient client;

    public AiRootCauseService(CaseRepository caseRepository, RefundRepository refundRepository,
                              RefundEventRepository refundEventRepository, SettlementRowRepository settlementRowRepository,
                              ObjectMapper objectMapper,
                              @Value("${refundguard.ai.anthropic-api-key:}") String apiKey,
                              @Value("${refundguard.ai.model:claude-opus-5-5}") String model) {
        this.caseRepository = caseRepository;
        this.refundRepository = refundRepository;
        this.refundEventRepository = refundEventRepository;
        this.settlementRowRepository = settlementRowRepository;
        this.objectMapper = objectMapper;
        this.apiKey = apiKey;
        this.model = model;
    }

    public boolean isEnabled() {
        return apiKey != null && !apiKey.isBlank();
    }

    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", isEnabled());
        m.put("model", isEnabled() ? model : null);
        m.put("mode", isEnabled() ? "CLAUDE" : "RULES_ONLY");
        return m;
    }

    public Map<String, Object> analyzeCase(Long caseId) {
        Case c = caseRepository.findById(caseId).orElseThrow(() -> new IllegalArgumentException("Case not found: " + caseId));
        Refund refund = c.getRefundId() != null ? refundRepository.findById(c.getRefundId()).orElse(null) : null;
        SettlementRow row = c.getSettlementRowId() != null ? settlementRowRepository.findById(c.getSettlementRowId()).orElse(null) : null;
        List<RefundEvent> events = refund != null ? refundEventRepository.findByRefundIdOrderByEventTimeAsc(refund.getId()) : List.of();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("caseId", caseId);

        if (!isEnabled()) {
            // Refresh the deterministic analysis with the latest data.
            if (refund != null || row != null) {
                RuleBasedRootCauseAnalyzer.Analysis a = RuleBasedRootCauseAnalyzer.analyze(c.getIssueType(), refund, row);
                apply(c, a.hypothesis(), a.recommendedAction(), a.customerMessage(), "RULES");
            }
            out.put("source", "RULES");
            out.put("message", "Claude AI is not configured (set ANTHROPIC_API_KEY). Rule-based analysis refreshed.");
            out.put("case", c);
            return out;
        }

        try {
            String facts = buildFacts(c, refund, row, events);
            MessageCreateParams params = MessageCreateParams.builder()
                    .model(model)
                    .maxTokens(16000L)
                    .system(SYSTEM_PROMPT)
                    .addUserMessage("Analyse this refund discrepancy case and reply with the JSON object only.\n\nCASE FACTS:\n" + facts)
                    .build();

            Message response = client().messages().create(params);

            String stopReason = response.stopReason().map(Object::toString).orElse("");
            if (stopReason.toLowerCase().contains("refusal")) {
                out.put("source", c.getAiSource());
                out.put("message", "Claude declined to analyse this case; kept the rule-based analysis.");
                out.put("case", c);
                return out;
            }

            String text = response.content().stream()
                    .flatMap(block -> block.text().stream())
                    .map(t -> t.text())
                    .collect(Collectors.joining("\n"));

            JsonNode json = objectMapper.readTree(extractJson(text));
            String hypothesis = json.path("hypothesis").asText(null);
            String action = json.path("recommendedAction").asText(null);
            String customer = json.path("customerMessage").asText(null);
            if (hypothesis == null || hypothesis.isBlank()) throw new IllegalStateException("Model reply had no hypothesis");

            apply(c, hypothesis, action, customer, "CLAUDE (" + model + ")");
            out.put("source", c.getAiSource());
            out.put("message", "Case analysed by Claude.");
        } catch (Exception e) {
            log.error("Claude analysis failed for case {}", caseId, e);
            out.put("source", c.getAiSource());
            out.put("message", "Claude analysis failed (" + e.getClass().getSimpleName() + "): kept the existing analysis.");
        }
        out.put("case", c);
        return out;
    }

    private void apply(Case c, String hypothesis, String action, String customer, String source) {
        c.setAiRootCauseHypothesis(hypothesis);
        if (action != null) c.setRecommendedAction(action);
        if (customer != null) c.setCustomerMessage(customer);
        c.setAiSource(source);
        c.setAiAnalyzedAt(LocalDateTime.now());
        caseRepository.save(c);
    }

    /** Facts only - deliberately excludes customer name and email. */
    String buildFacts(Case c, Refund refund, SettlementRow row, List<RefundEvent> events) throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        ObjectNode cs = root.putObject("case");
        cs.put("issueType", c.getIssueType());
        cs.put("severity", c.getSeverity());
        cs.put("openedAt", String.valueOf(c.getCreatedAt()));
        cs.put("currentRuleBasedHypothesis", c.getAiRootCauseHypothesis());
        root.put("now", LocalDateTime.now().toString());

        if (refund != null) {
            ObjectNode r = root.putObject("refund");
            r.put("orderId", refund.getOrderId());
            r.put("gatewayRefundId", refund.getProviderRefundId());
            r.put("paymentMethod", refund.getPaymentMethod());
            r.put("expectedAmount", String.valueOf(refund.getAmount()));
            r.put("settledAmount", String.valueOf(refund.getSettledAmount()));
            r.put("status", String.valueOf(refund.getDerivedStatus()));
            r.put("lastGatewayEvent", refund.getGatewayStage());
            r.put("bankUtr", refund.getBankUtr());
            r.put("requestedAt", String.valueOf(refund.getRequestedAt()));
            r.put("settledAt", String.valueOf(refund.getSettledAt()));
            ObjectNode sla = r.putObject("rbiTatSla");
            sla.put("tat", "T+" + refund.getSlaTatDays());
            sla.put("deadline", String.valueOf(refund.getSlaDueAt()));
            sla.put("state", refund.getSlaState());
            sla.put("daysOverdue", refund.getDaysOverdue());
            sla.put("estimatedCompensationInr", String.valueOf(refund.getCompensationDue()));
        }
        if (row != null) {
            ObjectNode s = root.putObject("settlementRow");
            s.put("reference", row.getRefundReference());
            s.put("utr", row.getUtrNumber());
            s.put("amount", String.valueOf(row.getAmount()));
            s.put("settlementDate", String.valueOf(row.getSettlementDate()));
        }
        ArrayNode tl = root.putArray("timeline");
        for (RefundEvent e : events) {
            ObjectNode n = tl.addObject();
            n.put("time", String.valueOf(e.getEventTime()));
            n.put("source", e.getSource());
            n.put("type", e.getEventType());
        }
        return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
    }

    static String extractJson(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) throw new IllegalStateException("No JSON object in model reply");
        return text.substring(start, end + 1);
    }

    private AnthropicClient client() {
        if (client == null) {
            synchronized (this) {
                if (client == null) {
                    client = AnthropicOkHttpClient.builder().apiKey(apiKey).build();
                }
            }
        }
        return client;
    }
}
