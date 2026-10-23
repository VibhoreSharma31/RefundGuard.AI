# RefundGuard.AI – Enhancement Report

**Date:** 18 Oct 2026
**Scope:** RBI TAT SLA engine (High-impact #1) + gap fixes 1, 2, 4, 5 and 6

---

## 1. Summary

| # | What | Status |
|---|------|--------|
| High-impact #1 / Gap 1 | **Real RBI TAT SLA / deadline engine**: per-payment-method deadlines, "Due in X days" / "Overdue by X days", compensation estimate, scheduled check | Added |
| Gap 2 | **Unknown bank credits** (settlement rows with no matching refund) now open an `UNMATCHED_SETTLEMENT` case instead of being silently ignored | Fixed |
| Gap 4 | **Razorpay webhook signature verification** (HMAC-SHA256) on a new real webhook endpoint | Added |
| Gap 5 | **Real AI root-cause analysis** using Claude (`claude-opus-5-5`), plus a data-driven rules engine as the fallback | Added |
| Gap 6 | **`refund.failed` now opens a CRITICAL `REFUND_FAILED` case** | Fixed |

Also fixed along the way:
- Duplicate bank credits for the same refund now open a `DUPLICATE_SETTLEMENT` case (possible double refund).
- Cases auto-resolve when the bank settlement arrives.
- A late webhook can no longer overwrite a settled refund back to "processed".
- The refund timeline modal showed "Invalid Date". It now uses `eventTime`.
- The CSV format hint said `settlement_amount`, but the code expects `amount`. The hint is corrected.
- The new-order toast showed `undefined` as the order ID. Fixed.
- "RBI TAT COMPLIANT" was hard-coded text. The badge and status bar now show live SLA data.

---

## 2. Feature details

### 2.1 RBI TAT SLA engine (High-impact #1, Gap 1)

**Before:** `checkOverdueRefunds()` took every `GATEWAY_PROCESSED` refund missing from the latest bank file and marked it `POTENTIAL_DELAY` immediately. It ignored time and payment method, so a refund processed 1 minute earlier counted as "delayed".

**Now:**
- When a refund is created, it is stamped with its policy (`slaTatDays`, `slaDueAt`, `compensationPerDay`). The snapshot means a later policy change never silently moves an existing deadline.
- **Deadline = 23:59:59 IST on calendar day T + N**, where T is the day the refund was initiated. This follows RBI's convention that T is the calendar date.
- Default policy, configurable in `application.properties`:

  | Method | Deadline | Comp./day |
  |---|---|---|
  | UPI, IMPS, NEFT, RTGS | T+1 | ₹100 |
  | CARD, WALLET, NETBANKING | T+5 | ₹100 |
  | anything else | T+5 | ₹100 |

  These defaults are based on the RBI TAT circular for failed transactions (RBI/2019-20/67, 20 Sep 2019). For voluntary merchant refunds, align them with your gateway agreement. The compensation figure is an **estimate of exposure**, not a legal calculation.
- SLA states (computed live): `ON_TRACK`, `DUE_SOON` (due today or tomorrow), `BREACHED`, `MET`, `MET_LATE`, `NOT_TRACKED`, `CLOSED`.
- Actions taken by the engine:
  - `DUE_SOON` sets status `POTENTIAL_DELAY` as an early warning. No case is opened.
  - `BREACHED` sets the new status `SLA_BREACHED` and opens a **HIGH `SLA_BREACH` case**. The case shows days overdue and the compensation exposure, both refreshed on every run.
  - When the bank settlement arrives, `settledAt` is set to the bank's settlement date and compensation stops growing. Open SLA, missing-settlement and failed cases are **auto-resolved** with a note saying whether the refund was on time or N days late.
- The check runs automatically every 5 minutes (`@Scheduled`), after every reconciliation, and on demand (`POST /api/sla/check`).
- The JVM runs in **Asia/Kolkata** time. Docker defaults to UTC, which would shift every deadline by 5h30m.

**Dashboard:** new "RBI TAT SLA" column (for example "Due in 3 days" or "Overdue by 2 days · ₹200 exposure"), new metric cards for SLA Breached, Compensation Exposure and on-time settlement %, a live header badge and status bar, and an SLA Policy table.

**Demo tool:** "Time-travel refund (+days)" moves a refund's start date back so you can see a breach without waiting days.

### 2.2 Unknown bank credits (Gap 2)

**Before:** a bank settlement row whose reference matched no refund was skipped silently. For example, `rfnd_ord5894` in `settlement_with_mismatch.csv` was lost.

**Now:** the row opens a **HIGH `UNMATCHED_SETTLEMENT` case** with no refund attached, linked to the settlement row. It lists the amount, UTR, the bank parsed from the UTR, and the likely causes (manual dashboard refund, wrong reference, wrong beneficiary). If a later reconciliation run matches the row, the case is auto-resolved.

### 2.3 Signed Razorpay webhooks (Gap 4)

**Before:** no real webhook endpoint existed. Events came only through unauthenticated simulator endpoints.

**Now:** `POST /api/webhooks/razorpay`
- Reads the **raw body** and verifies `X-Razorpay-Signature = HMAC-SHA256(rawBody, RAZORPAY_WEBHOOK_SECRET)` using a constant-time comparison.
- **Fails closed:** no secret configured → 503, wrong signature → 401.
- Uses `X-Razorpay-Event-Id` as the idempotency key, so duplicate webhooks are ignored.
- Parses Razorpay's refund payload: amount in paise → ₹, `notes.order_id`, and payment method.
- Returns 200 for accepted, duplicate and ignored events, so Razorpay does not retry endlessly.

**Demo tool:** "Test Signed Webhook" sends a genuine signed payload (accepted) and a tampered one with the amount changed (rejected) through the real verification code.

### 2.4 Real AI root-cause analysis (Gap 5)

**Before:** the "AI hypothesis" was a fixed sentence per issue type.

**Now, in two layers:**
1. **Rules engine** (`RuleBasedRootCauseAnalyzer`), always on and built from real numbers:
   - Amount mismatch: detects a gateway MDR/fee deduction (1.5–3% short), an exact 50% partial refund, an over-credit (loss risk), or an unexplained difference. Includes the bank name parsed from the UTR.
   - SLA breach: method, T+N, deadline, days late, compensation, and *where* the refund is stuck (merchant, gateway or bank), based on the last gateway event.
   - Failed refund: method-specific cause (closed VPA or account, expired card, wallet KYC).
   - Unmatched or duplicate settlement.
   - Each analysis gives a hypothesis, recommended actions, and a **draft message to the customer**.
2. **Claude AI** (official Anthropic Java SDK, model `claude-opus-5-5`):
   - Run on demand with the **"Analyze with AI"** button in the case view (`POST /api/cases/{id}/analyze`), so cost stays under the operator's control.
   - Sends only operational facts: amounts, statuses, method, UTR, timeline and SLA. **Customer name and email are never sent.**
   - Returns a hypothesis, recommended action and customer message, stored with `aiSource = "CLAUDE (claude-opus-5-5)"`.
   - Without `ANTHROPIC_API_KEY`, or if the call fails or the model declines, the rules analysis is kept.
   - All AI text is HTML-escaped in the UI.

### 2.5 Failed refunds (Gap 6)

**Before:** `refund.failed` only set the status `FAILED_REPORTED`. No case was opened, so the customer stayed unpaid without anyone being alerted.

**Now:** a **CRITICAL `REFUND_FAILED` case** opens with a method-specific cause, the steps to re-initiate via IMPS/NEFT, and a customer message asking for alternate bank details. The SLA clock keeps running, so if the refund isn't re-issued it also becomes an SLA breach.

**Demo tool:** "Simulate Refund FAILED".

---

## 3. Files changed / added

**New**
- `sla/SlaCalculator.java`: pure deadline, state and compensation maths
- `sla/SlaPolicyProperties.java`: TAT policy per payment method (from properties)
- `sla/SlaService.java`: applies the policy, scheduled breach check, opens and refreshes cases
- `service/CaseService.java`: one place to open cases (no duplicates), auto-resolve, and orphan cases
- `ai/RuleBasedRootCauseAnalyzer.java`: data-driven root-cause analysis
- `ai/AiRootCauseService.java`: Claude analysis with fallback
- `webhook/RazorpaySignatureVerifier.java`, `webhook/RazorpayWebhookService.java`
- `controller/WebhookController.java`
- `config/SchemaUpgradeRunner.java`: upgrades an existing Oracle schema safely
- Tests: `SlaCalculatorTest`, `RazorpaySignatureVerifierTest`, `RuleBasedRootCauseAnalyzerTest`

**Modified**
- `model/Refund.java`: SLA fields, `settledAt`, `gatewayStage`, computed SLA getters
- `model/Case.java`: nullable `refundId`, `settlementRowId`, `customerMessage`, `aiSource`, `aiAnalyzedAt`, `compensationDue`, 4000-char text columns
- `model/RefundStatus.java`: `SLA_BREACHED`
- `repository/CaseRepository.java`, `repository/RefundRepository.java`: new queries
- `service/IngestionService.java`: SLA stamping, failed-refund case, no status downgrade, lookup by gateway refund ID
- `service/ReconciliationService.java`: orphan and duplicate detection, `settledAt`, auto-resolve, SLA engine
- `simulator/SimulatorService.java`: fail-refund, time-travel and signed-webhook demos
- `controller/ApiController.java`: new endpoints, SLA metrics in the summary, JSON error handler
- `RefundGuardApplication.java`: IST timezone
- `resources/application.properties`: SLA policy, webhook secret, AI key and model
- `resources/static/index.html`: dashboard changes listed above
- `pom.xml`: `com.anthropic:anthropic-java:2.34.0`
- `docker-compose.yml`: `RAZORPAY_WEBHOOK_SECRET`, `ANTHROPIC_API_KEY`, `TZ`

### New API endpoints
| Method | Path | Purpose |
|---|---|---|
| GET | `/api/sla/policy` | TAT policy table |
| POST | `/api/sla/check` | Run the SLA check now |
| GET | `/api/ai/status` | Whether Claude is configured |
| POST | `/api/cases/{id}/analyze` | AI root-cause analysis for a case |
| POST | `/api/webhooks/razorpay` | Signed Razorpay webhook receiver |
| POST | `/api/simulator/fail-refund?orderId=` | Demo: gateway reports refund failed |
| POST | `/api/simulator/age-refund?orderId=&days=` | Demo: time-travel a refund |
| POST | `/api/simulator/signed-webhook?orderId=` | Demo: genuine vs tampered webhook |

---

## 4. Configuration

| Env var | Meaning | Default |
|---|---|---|
| `RAZORPAY_WEBHOOK_SECRET` | Must match Razorpay Dashboard → Webhooks | `rg_demo_webhook_secret_change_me` in docker-compose; **change it** |
| `ANTHROPIC_API_KEY` | Enables Claude analysis | empty = rules only |
| `REFUNDGUARD_AI_MODEL` | Claude model | `claude-opus-5-5` |
| `refundguard.sla.*` | TAT days and compensation per method, check interval | see `application.properties` |

To enable AI: `set ANTHROPIC_API_KEY=sk-ant-...` (Windows) before `docker compose up --build`.

---

## 5. How to demo (5 minutes)

1. `docker compose up --build`, then open http://localhost:8080 and click **Reset DB**.
2. Cancel **ORD-9001** (UPI) and **ORD-9002** (Card). The SLA column shows "Due tomorrow" for UPI (T+1) and "Due in 5 days" for Card (T+5). No false alarm.
3. In Simulator Tools, enter `ORD-9001` with **3** days and click **Time-travel refund**. The refund becomes `SLA_BREACHED`, "Overdue by 2 days", ₹200 exposure, and an SLA_BREACH case opens. The header badge turns red.
4. Upload `settlement_with_mismatch.csv`. You'll see an AMOUNT_MISMATCH case for ORD-9003 (if cancelled) and an **UNMATCHED_SETTLEMENT** case for `rfnd_ord5894`.
5. Enter `ORD-9003` and click **Simulate Refund FAILED**. A CRITICAL REFUND_FAILED case opens.
6. Click **Test Signed Webhook**. The tampered payload is rejected and the genuine one is accepted.
7. Open any case and click **Analyze with AI**. With an API key, Claude writes the analysis. Without one, the rules analysis is refreshed.

---

## 6. Verification status (please read)

- **The Java code was NOT compiled or run here.** This machine has no JDK, Maven or Docker, and the downloads were declined. Build it with `docker compose up --build`. The Dockerfile runs `mvn package -DskipTests`; to run the new unit tests use `mvn test`.
- The code was reviewed by hand against the existing code and the SDK documentation.
- **Dashboard JavaScript was tested** in a browser against stub API data: no console errors, and the SLA chips, badge, metrics, policy table, case view and HTML escaping all work.
- **Dependency risk to watch on the first build:** `anthropic-java` (Kotlin-based) alongside Spring Boot 3.3.4's managed Kotlin and OkHttp versions. If you see a `NoSuchMethodError` from `kotlin.*` or `okhttp3.*` at startup, add `<kotlin.version>` / `<okhttp.version>` properties in `pom.xml` matching the versions `anthropic-java` requires.
- **Existing Oracle data:** `SchemaUpgradeRunner` patches the old schema (nullable `refund_id`, longer text columns, new enum value). For a guaranteed clean start, run `docker compose down -v` (this **deletes** the demo DB volume).
