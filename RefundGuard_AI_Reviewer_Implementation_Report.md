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
