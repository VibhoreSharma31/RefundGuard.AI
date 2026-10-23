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
