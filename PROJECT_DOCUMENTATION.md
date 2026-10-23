# RefundGuard.AI - Project Documentation

---

## Source: RISK_SCORING_IMPLEMENTATION.md

# RefundGuard.AI: Explainable Refund Risk Scoring (Admin Portal)

## 1. Build and test results
| Check | Result |
|---|---|
| `mvn test` (JDK 21, Maven 3.9.9) | **Passed: 129 tests, 0 failures, 0 errors, BUILD SUCCESS**: 106 existing + 23 new (14 engine/explainer unit tests, 9 API/persistence integration tests). Run on **H2 in Oracle mode** (existing `test` profile) |
| Oracle database | **NOT verified.** No Oracle instance is reachable from the build machine. `V4__risk_factors.sql` was not executed against Oracle |
| Frontend | No build step exists (a single static `index.html`). The page was loaded in a browser through a local static server: **no JavaScript errors**, and the new view and detail renderer were exercised with a sample API payload. **Not** exercised against the running backend |
| Running app end to end | Not done |

**Bug found and fixed during testing:** Hibernate rendered `LIKE … ESCAPE ''`. Oracle (and H2 in Oracle mode) reads `''` as NULL, so the search never matched. The search now uses an explicit escape character, and user `%`, `_` and `\` are escaped rather than interpreted.

## 2. What existed and what was added
The project **already had** a rule-based engine:
- `RiskScoringEngine` with weights in `RiskConfig` / `refundguard.risk.*`;
- assessment history in `rg_risk_assessments`;
- staleness detection;
- auto-hold into the existing Hold-and-Release review workflow.

It was **extended, not duplicated**. No Admin Portal *UI* existed: admins only had `/api/admin/*` endpoints. The Risk Analytics section was therefore added as an admin-only view of the existing single-page UI, using its existing card, table, pill and modal styles.

Added:
- the requested score bands;
- evidence for each factor, `riskDescription` and recommended next action;
- three new data-backed rules, plus overlap (no double-counting) handling;
- policy-violation holds;
- a factor child table;
- re-scoring after settlement;
- deduplicated, audited manual reassessment;
- admin analytics API and UI.

## 3. Architecture
```
Refund / Order / RefundEvent / Case / SettlementRow (existing tables)
        │  RiskScoringService.buildInput()   - real data only; null = "unavailable"
        ▼
RiskScoringEngine.assess()  (pure, deterministic; no status input) ─► RiskResult (score, level, factors+evidence)
        │  RiskScoringService.assess()  - dedupe, persist
        ▼
rg_risk_assessments (1 row / assessment)  +  rg_risk_factors (1 row / triggered rule)
        │                                   │
        ├─ RefundReviewService.evaluateAndHold()  (existing Hold-and-Release; score or policy violation -> HOLD only)
        └─ RiskAnalyticsService / RiskAnalyticsController  ─► Admin "Risk Analytics" view (index.html)
RiskExplainer: plain-language description + recommended actions (pure functions over stored factors)
```

**When scores are (re)calculated:**
- **Ingestion** (existing). Unchanged inputs reuse the latest assessment.
- **Settlement**: new. After reconciliation, every refund whose settlement data changed is re-scored, again reusing the latest assessment when inputs are unchanged.
- **Manual reassess** (existing endpoint). Identical inputs within `reassess-dedupe-seconds` (default 60) reuse the latest assessment, so repeated requests create no duplicate rows. Otherwise a new assessment is stored and audited.
- **Reviewer decision** (existing). Always stored.
- **Admin back-fill**: new, for refunds never assessed.

## 4. Rules and weights (proposals, not validated fraud probabilities)
The score is the sum of points, **capped at 100**; each rule only adds points. **Processing status is never an input**: `REQUESTED`, `GATEWAY_*`, `POTENTIAL_DELAY`, `AMOUNT_MISMATCH`, `FAILED_REPORTED` and the other statuses add nothing by themselves (a test covers this). All weights are configurable under `refundguard.risk.*`.

| Code | Pts | Data used | Notes |
|---|---|---|---|
| HIGH_AMOUNT / VERY_HIGH_AMOUNT | 20 / 35 | refund amount vs ₹25,000 / ₹100,000 | tiered; the higher tier replaces the lower |
| EXCEEDS_ORDER_AMOUNT | 40 | refund amount vs order amount | **policy violation** |
| CUMULATIVE_EXCEEDS_ORDER | 40 | sum of non-rejected refunds of the order vs order amount | **policy violation**; replaces MULTIPLE_REFUNDS_FOR_ORDER; not added when the refund alone already exceeds |
| ORDER_CURRENCY_MISMATCH | 30 | refund vs order currency | **policy violation** |
| MULTIPLE_REFUNDS_FOR_ORDER | 30 | refund rows with the same order id | |
| REPEATED_ATTEMPTS | 15 / 25 | `refund.created` gateway events (2 / 3+) | tiered |
| CUSTOMER_REFUND_FREQUENCY | 20 / 30 | refunds of the same customer e-mail in 7 days (3 / 5+) | tiered; only when an e-mail exists |
| CUSTOMER_REFUND_TOTAL | 15 | same customer's 7-day total ≥ ₹100,000 | |
| SETTLEMENT_AMOUNT_MISMATCH | 45 over / 20 short | `settled_amount` vs refund amount | over-credit = possible over-refund; shortfall is often operational |
| DUPLICATE_SETTLEMENT | 40 | ≥ 2 distinct UTRs on matched settlement rows | |
| OPEN_DISCREPANCY | 15 | open AMOUNT_MISMATCH / DUPLICATE_SETTLEMENT / REFUND_FAILED cases | cases describing a fact already scored by a settlement rule are **not counted again** |
| ORDER_NOT_FOUND | **0** (default) | order record | reported as *unavailable*, not risk (was 10 in V1) |

**Score bands** (configurable as `medium-min`, `high-min`, `critical-min`):
- LOW: 0–29
- MEDIUM: 30–59
- HIGH: 60–79
- CRITICAL: 80–100

**Missing data:** the rule is skipped and listed under `notEvaluated` ("unknown / unavailable"). It is never scored. A refund that hasn't been settled yet is neither risk nor missing data.

**Policy violations vs. hard blocks:** refunds arrive from the payment gateway after the gateway has already accepted them, so the application cannot block them outright. The strongest safe action available is a **forced hold**: with `hold-on-policy-violation=true`, a policy violation places a holdable refund `ON_HOLD` through the existing review workflow whatever the score. The score-based hold threshold is **unchanged** (`hold-min-score=50`, the existing policy). Note that 50 now falls inside the MEDIUM band; change it via `REFUNDGUARD_RISK_HOLD_MIN_SCORE` if your policy requires.

Rule version: `RULE_BASED_V2`. V1 assessments stay readable and are flagged "outdated" until they are reassessed.

## 5. Oracle schema changes (`db/migration/V4__risk_factors.sql`, additive only)
- **New table `rg_risk_factors`:**
  - `id` (identity primary key);
  - `assessment_id` NOT NULL, **foreign key → `rg_risk_assessments(id)` ON DELETE CASCADE**;
  - `ordinal`;
  - `rule_code` VARCHAR2(50);
  - `points` NUMBER, CHECK 0–100;
  - `reason` VARCHAR2(500);
  - `evidence_json` VARCHAR2(1000);
  - `policy_violation` NUMBER(1).
  - Indexes on `assessment_id` and `rule_code`.
- **`rg_risk_assessments`:**
  - new column `policy_violation` NUMBER(1), nullable; legacy rows read as false;
  - new index `(refund_id, id)`, used for "latest assessment per refund".
  - New assessments leave `indicators_json` NULL, so data isn't stored twice; old rows still use it as a fallback. Previously, a factor list longer than 4000 characters was silently stored as `[]`.

With the existing `ddl-auto=update`, Hibernate creates these objects on startup. The script is for manually managed schemas. Nothing is dropped or recreated. Rollback steps are in the script.

## 6. Endpoints and permissions
| Method & path | Permission | Purpose |
|---|---|---|
| `GET /api/admin/risk/summary` | RISK_ANALYTICS_VIEW | Assessed/unassessed, distribution, high+critical, amount mismatches, pending reviews, policy violations, outdated scores, bands |
| `GET /api/admin/risk/refunds` | RISK_ANALYTICS_VIEW | Server-side filter and paging: `q`, `level` (incl. UNASSESSED), `status`, `from`/`to` (requested date), `mismatch`, `policyViolation`, `page`, `size` ≤ 100, `sort`, `dir` |
| `GET /api/admin/risk/refunds/{id}` | RISK_ANALYTICS_VIEW | Score, level, `riskDescription`, `riskFactors` (points, reason, evidence, policyViolation), `notEvaluated`, `recommendedAction(s)`, `ruleVersion`, history, settlement rows, open review |
| `POST /api/admin/risk/assess-unassessed` | RISK_ANALYTICS_VIEW **and** RISK_ASSESS | Back-fill (max 500). Assessment only; never holds or releases |
| `POST /api/refunds/{id}/risk/reassess` (existing) | RISK_ASSESS | Now deduplicated and audited (`risk.reassessed`); response includes `reused` |
| `GET /api/refunds/{id}/risk` (existing) | RISK_VIEW | Now also returns the V2 fields |

- `RISK_ANALYTICS_VIEW` is a new permission held by ADMIN only. It is enforced on the server with `@PreAuthorize`, and tests confirm 401 without login and 403 for operators and reviewers.
- Release stays reviewer-only, through the existing Hold-and-Release workflow. Scoring never releases a refund or changes its status.
- Audit ledger (`rg_refund_events`, source `RISK_ENGINE`): manual reassessments that store a new assessment, and back-fills, are logged with the actor. The refund timeline shows them.

## 7. Admin UI (index.html)
A header button, **Risk Analytics**, is shown only with `RISK_ANALYTICS_VIEW`. The view contains:
- **Metric cards:** assessed of total, high/critical, amount mismatches, pending reviews, policy violations, outdated scores.
- **Distribution chart:** level bars with band ranges.
- **Scored-refund table:** server-side search, filters (level, status, date range, mismatch, policy violation), sort and paging. Each row shows refund, order, amount and currency, status, score, level, assessed time and a short explanation.
- **Detail modal:** score, level, description, recommended actions, a factor table with points and evidence, unavailable data, refund/payment/settlement data, history, links to the open review, and a "Reassess now" button.
- Loading, empty and error-with-retry states throughout.

All numbers come from the API; nothing is hard-coded or mocked.

## 8. Assumptions and limitations
- **No settlement-currency rule.** The CSV import hard-codes every settlement row's currency to `INR`, so that data can't support the rule.
- **No "missing evidence" rule.** The application has no evidence requirements.
- **No original-payment table.** The order amount (`rg_orders.amount`) is used as the paid amount, and "refundable balance" = order amount − other non-rejected refunds of the same order. Partial captures and fees are not modelled.
- **Settlement rows** are linked to a refund by `refund_reference` matching the gateway refund id or the order id, the same rule reconciliation uses.
- **"Latest assessment"** in analytics queries = the highest id per refund (ids are assigned in assessment order).
- **Weights are judgement-based**, not calibrated on fraud outcomes. Review them against your own data before relying on them.
- **No pagination convention** existed in the project; the list returns `{content, page, size, totalElements, totalPages}`.




---

## Source: RefundGuard_AI_Reviewer_Implementation_Report.md

# RefundGuard.AI: Reviewer RBAC & Review Dashboard, Implementation Report

## 1. Build and test status

Results from the second round (portable JDK 21 + Maven 3.9.9; tools are not shipped):

| Check | Result |
|---|---|
| `mvn test-compile` | **Passed.** Main and test code compile with no errors |
| `mvn test` (all unit + integration tests) | **Passed: 106 tests, 0 failures, 0 errors (BUILD SUCCESS)**, on H2 in Oracle mode (existing `test` profile). Includes the 20 reviewer-workflow integration tests and the existing hold-and-release, RBAC and risk API tests |
| Oracle database | **NOT verified.** No Oracle instance or Docker was available. The flow has not run against Oracle, and the `V3` script and the `SchemaUpgradeRunner` statement were not executed against Oracle |
| Live app in a browser against the real backend | **NOT done.** It was interrupted at your request; the last start attempt exited with an error that was not investigated |
| `mvn package` (jar) | **NOT RUN** |

**Fixes made in the second round (test files only, no production code changed):**
1. **Bug already present in the original upload:** `application-test.properties` bootstrapped users with passwords that contain the username (e.g. `Test-admin-pass-1234` for `admin`). The existing `PasswordPolicy` rejects those, so the test Spring context never started and **none of the original integration tests could pass**. I replaced the test-only passwords with compliant ones (`Rg-Test-Pass-A/O/R/R2-1234`) in the test properties and the four integration test classes. The password policy is unchanged.
2. One new test sent a 4-character Start Review note. That is below the 5-character minimum enforced whenever a note is given, so the server correctly returned 400. I lengthened the note in the test.

**Second-round inspection of your 7 points:** all were already implemented in round 1 (the table in §3). No production code was missing, so none was added.

**To verify against your Oracle database** (set `SPRING_DATASOURCE_PASSWORD` and `RG_*_PASSWORD` as in `.env.example`):

```bash
mvn -q test
mvn spring-boot:run
```

Then, in the UI:
1. Sign in as an operator and press **Flag** on a refund in the Refunds Ledger.
2. Sign in as a reviewer and open the case on the dashboard: Start Review → Approve or Reject.
3. Check `rg_refund_reviews`, `rg_refunds.derived_status` and `rg_refund_events` (source `REVIEW_WORKFLOW`).

| Earlier check | Result |
|---|---|
| Frontend build | Not applicable. The frontend is a single static page (`src/main/resources/static/index.html`, plain JavaScript + Tailwind CDN); there is no React project or build step in the uploaded ZIP |
| Frontend smoke test | **RUN, passed with canned data.** `index.html` was served with a throwaway local mock API (not shipped) and driven in a browser as REVIEWER and as OPERATOR. Checked: dashboard cards, queue, case details, Start Review → Approve flow, the reason-required check, HTML escaping of user text, operator Flag button and flag dialog, operations view unchanged. **No JavaScript console errors.** This does **not** exercise the real backend. |
| Static cross-check | Every new service/repository method call was checked against its definition, and the source was diffed against the uploaded ZIP (20 files changed, none deleted) |

Integration tests use the existing `test` profile: H2 in Oracle mode, with users bootstrapped from `application-test.properties`. No external services are needed.

## 2. What already existed and was reused (no duplicate systems)

The uploaded project already had:
- the `REVIEWER` role and the `REVIEW_VIEW` / `REVIEW_DECIDE` permissions;
- HTTP Basic login against `rg_users` (BCrypt);
- the hold-and-release review table `rg_refund_reviews` with a row lock and `@Version`;
- the audit ledger `rg_refund_events`;
- `HIGH_RISK_REVIEW` cases and a simulated release.

The feature **extends** these. There is no new user table, no new auth mechanism, no new case table and no new audit store.

## 3. Changes

### Security / RBAC
- `Permission`: added `REVIEW_SUBMIT` (OPERATOR, ADMIN) and `REVIEW_ESCALATION_DECIDE` (ADMIN only; ADMIN gets every permission automatically). `REVIEW_DECIDE` stays REVIEWER + ADMIN. **Operators still cannot approve or reject.**
- Every endpoint has `@PreAuthorize`. Case-level rules are enforced server-side in `RefundReviewService.denial(...)`, the single rule set used both to **enforce** actions and to tell the UI which buttons to show. The rules:
  - legal state transition only;
  - **escalated** cases can be decided only by ADMIN;
  - a case **under review** belongs to its assigned reviewer (ADMIN can override);
  - **four-eyes**: whoever manually submitted a case cannot approve or reject it;
  - decisions only while the refund is `ON_HOLD`.
- Identity always comes from the security context; request bodies carry only `reason` / `note`. Extra JSON fields such as `submittedBy` or `role` are ignored, and a test covers this.
- No credentials were added or hard-coded.

### Case lifecycle (mapped onto the existing `rg_refund_reviews`)

| Requested state | Stored value | Notes |
|---|---|---|
| PENDING_REVIEW | `PENDING` | existing value kept, so old rows stay valid |
| UNDER_REVIEW | `UNDER_REVIEW` | new; assigns the case to the reviewer |
| MORE_INFO_REQUIRED | `MORE_INFO_REQUIRED` | new; back to operations; **refund stays ON_HOLD** |
| ESCALATED | `ESCALATED` | new; ADMIN-only decisions |
| APPROVED_FOR_RELEASE | `APPROVED` | existing value; UI label "Approved for release" |
| REJECTED | `REJECTED` | existing |
| (system) | `SUPERSEDED` | existing; bank settled while held |

Transitions (`ReviewStateMachine.allows/next`):
- START: PENDING → UNDER_REVIEW
- REQUEST_INFO: PENDING / UNDER_REVIEW / ESCALATED → MORE_INFO_REQUIRED
- RESUBMIT: MORE_INFO_REQUIRED → PENDING (operator/admin)
- ESCALATE: PENDING / UNDER_REVIEW → ESCALATED
- APPROVE / REJECT: PENDING / UNDER_REVIEW / ESCALATED → closed
- NOTE: any open case, state unchanged

Approve and reject are still allowed straight from PENDING so the existing approve/reject API and its tests keep working.

- **Operator submission** (`submitForReview`) puts the refund `ON_HOLD`, records the submitter, role, timestamp and reason, re-scores risk, and opens the existing `HIGH_RISK_REVIEW` case. Because the case type is reused, the generic "resolve case" endpoint still cannot bypass review.
- Manual submission is allowed from `REQUESTED, GATEWAY_PENDING, GATEWAY_PROCESSED, POTENTIAL_DELAY, SLA_BREACHED, FAILED_REPORTED`. It is refused for settled, closed, rejected or already-held refunds. On approval the refund returns to its pre-hold status.
- The automatic risk hold is unchanged.
- **Concurrency / duplicates:**
  - every action locks the refund row first (`SELECT … FOR UPDATE`, existing), then re-reads the case, so the loser of a race gets **409**;
  - `@Version` on the review row is a second guard;
  - a second submission while a case is open gets **409**.
- **Audit:** every action writes to the existing ledger (`rg_refund_events`, source `REVIEW_WORKFLOW`):
  - `review.submitted`
  - `review.started`
  - `review.info_requested`
  - `review.resubmitted`
  - `review.escalated`
  - `review.note`
  - `review.approved` / `review.rejected`
  - `review.released.simulated`
  - `review.superseded`

  Investigation notes are stored as these ledger entries, not in a separate table.
- **Release is simulated.** Approval only returns the refund to normal processing. The API and UI say explicitly that no real payment is initiated.

### API (all under `/api`, HTTP Basic)

| Method & path | Permission | Purpose |
|---|---|---|
| `POST /refunds/{id}/review/submit` `{reason}` | REVIEW_SUBMIT | Flag a refund / submit it for review |
| `GET /reviews?status=PENDING\|OPEN\|ALL\|<status>` | REVIEW_VIEW | Queue (existing endpoint, extended; default unchanged) |
| `GET /reviews/summary` | REVIEW_VIEW | Dashboard counts |
| `GET /reviews/{reviewId}` | REVIEW_VIEW | Full case context: refund, order, risk (current / at submission / history), related cases, settlement rows, mismatch/duplicate/SLA indicators, case activity, audit timeline, earlier reviews, `allowedActions` |
| `POST /reviews/{id}/start` | REVIEW_DECIDE | Start review (reason optional) |
| `POST /reviews/{id}/request-info` `{reason}` | REVIEW_DECIDE | Request more information |
| `POST /reviews/{id}/escalate` `{reason}` | REVIEW_DECIDE | Escalate to ADMIN |
| `POST /reviews/{id}/approve` `{reason}` | REVIEW_DECIDE | Approve release (simulated) |
| `POST /reviews/{id}/reject` `{reason}` | REVIEW_DECIDE | Reject release |
| `POST /reviews/{id}/resubmit` `{reason}` | REVIEW_SUBMIT | Answer an info request |
| `POST /reviews/{id}/notes` `{note}` | REVIEW_DECIDE or REVIEW_SUBMIT | Investigation note |
| `GET /refunds/{id}/review` | REVIEW_VIEW | Existing; now also returns `openReviewId`, `allowedActions`, `canSubmit` |
| `POST /refunds/{id}/review/approve\|reject` | REVIEW_DECIDE | Existing; same rules applied |

Errors use the existing shape:
- 400: missing or short reason
- 403: not permitted
- 404: not found
- 409: illegal transition, duplicate, or concurrent change

A reason of 5–500 characters is required for approve, reject, escalate, request-info, resubmit, submit and notes.

### Database
- `RefundReview` gains 14 **nullable** columns:
  - `origin`
  - `submitted_by`, `submitted_by_role`, `submission_reason`
  - `assigned_to`, `review_started_at`
  - `escalated_by`, `escalated_at`, `escalation_reason`
  - `info_requested_by`, `info_requested_at`, `info_request_reason`
  - `resubmission_count`
  - `last_action_at`

  With the existing `ddl-auto=update`, Hibernate adds them on startup. Existing rows stay valid: `origin` NULL is read as `AUTO_RISK`.
- `SchemaUpgradeRunner` (Oracle only, idempotent) drops the old CHECK constraint on `rg_refund_reviews.status`, so the new statuses are accepted on databases created by the previous version.
- `db/migration/V3__reviewer_case_workflow.sql` is the equivalent manual DDL, with rollback notes. **Not executed against a real Oracle instance.**
- No seed data was added.

### Frontend (`index.html`), additions only
- **Reviewer Dashboard** (new `<main id="reviewerMain">`, same card, metric, table, pill and modal styles):
  - 6 summary cards: Pending, High-risk, SLA breached, Escalated, Awaiting info, Completed;
  - review queue with search plus status / risk / origin / SLA-breached / assigned-to-me filters, risk and severity pills, submission time and age, origin and submitter, SLA chip;
  - loading, empty, error-with-retry, success-toast and inline-error states;
  - all counts come from `/api/reviews/summary`.
- **Case details modal:**
  - status, origin, risk and SLA header;
  - refund & order, submission & status;
  - risk with indicators and history;
  - mismatch/duplicate/failed/SLA indicators, related cases, settlement rows;
  - notes & decision history, earlier reviews, full audit timeline;
  - action panel showing only the buttons the server allows: **Start Review, Request More Information, Escalate, Reject Release, Approve Release (simulated), Add Note**; reasons are validated client-side and confirmed for approve/reject.
- Header: a **Reviewer Dashboard / Operations View** toggle, shown only with `REVIEW_DECIDE`. Reviewers land on the dashboard after sign-in; the bell "Review Queue" badge counts cases awaiting a decision and opens the dashboard.
- Operations view, minimal additions:
  - a **Flag** button in the Refunds Ledger (with `REVIEW_SUBMIT`, eligible statuses only) opens a reason dialog;
  - the existing High-Risk Review Queue now lists all open cases with a **Status** column;
  - the refund details panel shows new statuses, a **Resubmit** form when more information was requested, and an "Open case" link for reviewers;
  - the banner shows "N case(s) need more information" to operations.
- No existing section, layout or styling was redesigned or removed.

## 4. Reviewer setup and login
1. **New database (first start):** set `RG_REVIEWER_PASSWORD` in `.env` (see `.env.example`; at least 12 characters with a letter and a digit). The account `reviewer` is created only while `rg_users` is empty, exactly as before. `RG_OPERATOR_PASSWORD` works the same way for `operator`.
2. **Existing database:** as ADMIN, create a reviewer through the existing user API. Replace the placeholders with your own values; do not commit real passwords.
   ```bash
   curl -u admin:<admin-password> -H "Content-Type: application/json" -X POST http://localhost:8080/api/admin/users -d "{\"username\":\"<reviewer-name>\",\"password\":\"<strong-password>\",\"role\":\"REVIEWER\"}"
   ```
3. Open the app and sign in through the normal **Sign In** dialog. A REVIEWER lands on the Reviewer Dashboard; an ADMIN can switch with the header toggle.

## 5. Tests added or updated (all passing on H2; see §1)
- `ReviewerCaseWorkflowTest` (new, 20 integration tests, MockMvc + H2 + real bootstrapped users):
  - reviewer login and permissions;
  - 401 without credentials;
  - operator submission persisted with submitter, role, reason, previous status, case and audit event;
  - 400 without a reason; 403 for a reviewer submitting; 404 for an unknown refund;
  - 409 for a duplicate submission and for a settled refund;
  - client-supplied identity ignored;
  - queue, detail and summary visibility (and operators get no reviewer actions);
  - auto-held refunds shown as risk-engine cases;
  - full lifecycle: start → request info (refund stays ON_HOLD, approve gives 409) → resubmit → start → approve, with simulated release, status restored and all audit events present;
  - reasons required;
  - operators get 403 on every reviewer action;
  - reviewers cannot resubmit;
  - escalation is ADMIN-only;
  - an assigned case cannot be taken over;
  - four-eyes rule;
  - the case-resolve bypass is blocked;
  - notes are audited and shown;
  - **two concurrent conflicting decisions produce exactly one outcome and one audit event**;
  - summary counts come from persisted data.
- `ReviewStateMachineTest`: +5 tests (submittable statuses, the full transition table, illegal transitions, decidable states, restore after a manual hold).
- `RolePermissionsTest`: +1 test (who may submit, decide and decide escalations).
- Existing tests: logic unchanged; only their test passwords were replaced (see §1). All of them pass.

## 6. Known limitations
- Tested on H2 (Oracle mode) only. **Not verified against a real Oracle database**, and not exercised end to end through the running app in a browser (§1).
- Release is **simulated**; no payment integration exists or was added.
- In-app notification is the existing header badge, banner and toasts (no email or push; none existed before). The dashboard refreshes every 30 s, as the existing pages do.
- Notes are stored in the audit ledger (append-only by design), so they cannot be edited or deleted.
- Manual flagging is limited to refunds the bank has not settled yet. Settled or mismatched refunds are handled by the existing discrepancy-case workflow.
- While a refund is on hold, gateway webhooks are recorded on the timeline but do not change its status (existing behaviour). Approval restores the pre-hold status.
- Oracle DDL (`V3` script, `SchemaUpgradeRunner` statement) was not executed against a real Oracle database.
- No admin UI for user management exists in the page (unchanged); reviewer accounts are created by bootstrap or by the admin API.




---

## Source: README_DOCKER.md

# RefundGuard.AI — 1-Click Docker Setup Guide 🚀

Yeh project **Docker Compose** ke zariye kisi bhi computer (Windows, Mac, Linux) par bina kisi manual installation ke chal sakta hai.

---

## 📋 Prerequisites (Samne wale ke system me kya hona chahiye)
Samne wale ke paas bas ek cheez honi chahiye:
* **[Docker Desktop](https://www.docker.com/products/docker-desktop/)** (Installed aur chal raha ho).

> **Note:** Uske computer me Java, Oracle DB, ya Maven install karne ki **bilkul zaroorat nahi hai**! Docker sab kuch apne andar khud handle karega.

---

## ⚡ How to Run (Sirf 2 Steps)

### Step 1: Terminal / Command Prompt Kholein
Is project ke folder me jayein aur command prompt (CMD ya PowerShell ya Terminal) kholein:

```bash
cd refundguard-ai/backend
```

### Step 2: Project Start Karein
Sirf ye ek command run karein:

```bash
docker compose up --build
```

*(Pehli baar thoda samay lagega kyunki Docker official images aur build download karega)*

---

## 🌐 Project Access
Jab terminal me `Started RefundGuardApplication` likha aa jaye, browser kholein:

* **Dashboard Portal:** [http://localhost:8080/](http://localhost:8080/)
* **Swagger API Docs:** [http://localhost:8080/swagger-ui/index.html](http://localhost:8080/swagger-ui/index.html)

---

## 🛑 Project Stop Karna
Jab band karna ho:

```bash
docker compose down
```

---

## 🔑 Optional Settings (naye features ke liye)

Start karne se pehle (Windows CMD):

```bash
set RAZORPAY_WEBHOOK_SECRET=apna_razorpay_webhook_secret
set ANTHROPIC_API_KEY=sk-ant-...
docker compose up --build
```

* `RAZORPAY_WEBHOOK_SECRET` – Razorpay webhooks ka signature isi se verify hota hai (demo default already set hai, production me badlein).
* `ANTHROPIC_API_KEY` – "Analyze with AI" button ke liye (Claude). Na ho to rule-based analysis chalega.
* Purana DB volume hai aur koi schema error aaye to: `docker compose down -v` (yeh demo data delete karta hai).

Naye features ki details: `ENHANCEMENT_REPORT.md` aur `FEATURES_BEFORE_AFTER.txt`.

---

## 📁 Sample CSV Files for Testing
Testing ke liye settlement files project me shamil hain:
1. `sample_settlement_match.csv` — 100% matched reconciliation test.
2. `settlement_with_mismatch.csv` — Amount mismatch aur discrepancy case detection test.


---

## RBAC, risk scoring and hold-and-release (v1 update)

**Required before first start**

1. `cp .env.example .env` and edit it. `RG_DB_PASSWORD` is now required (no password is hard-coded any more).
   If you already have an `oracle-data` volume from the previous version, set it to the password that volume was
   created with (the old default was `RefundGuard123`), otherwise the app cannot connect.
2. Set `RG_ADMIN_PASSWORD` (min 12 chars, a letter and a digit). If you leave it empty, a random admin password is
   generated and printed ONCE in the app log (`docker compose logs refundguard-app | grep -A3 "Generated one-time"`).
3. Optionally set `RG_OPERATOR_PASSWORD` / `RG_REVIEWER_PASSWORD` to create demo `operator` / `reviewer` accounts.
   Bootstrap users are only created while the `rg_users` table is empty.

Then `docker compose up --build` and open http://localhost:8080 (the page asks you to sign in).

**New environment variables**: `RG_DB_PASSWORD`, `RG_ADMIN_USERNAME`, `RG_ADMIN_PASSWORD`, `RG_OPERATOR_PASSWORD`,
`RG_REVIEWER_PASSWORD`, `RG_SIMULATOR_ENABLED`, `REFUNDGUARD_RISK_HOLD_MIN_SCORE`. All risk weights/thresholds can be
overridden with Spring relaxed binding, e.g. `REFUNDGUARD_RISK_VERY_HIGH_AMOUNT=200000`.

**Database**: three new tables (`rg_users`, `rg_risk_assessments`, `rg_refund_reviews`) are created automatically by
Hibernate. Manual DDL: `db/migration/V2__rbac_risk_review.sql`.

**Demo**: sign in as admin, create an order of amount `150000` in "Place Order", then click Refund on it in the Orders
ledger. The refund is placed ON HOLD (very-high amount +35, customer total +15 = 50). Sign in as a REVIEWER
(or ADMIN), open the refund and approve/reject with a reason. Release on approval is SIMULATED.
See `RefundGuard_AI_Implementation_Report.md` for the full matrix, rules and test status.




---

## Source: ENHANCEMENT_REPORT.md

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



