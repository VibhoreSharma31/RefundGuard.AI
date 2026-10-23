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
