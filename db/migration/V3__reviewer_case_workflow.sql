-- RefundGuard.AI  V3: reviewer case workflow (Oracle 21c/23c)
--
-- You normally do NOT need to run this: with spring.jpa.hibernate.ddl-auto=update Hibernate adds the new
-- nullable columns on startup, and SchemaUpgradeRunner drops the old status CHECK constraint.
-- This script is the equivalent for environments where DDL is applied manually (ddl-auto=validate/none).
-- It only ADDS columns and widens the allowed review statuses; existing review rows stay valid
-- (origin NULL is read as AUTO_RISK; PENDING / APPROVED keep their meaning).
-- STATUS: written to mirror the JPA mappings; NOT executed against a real Oracle instance in this delivery.

ALTER TABLE rg_refund_reviews ADD (
    origin               VARCHAR2(20 CHAR),
    submitted_by         VARCHAR2(64 CHAR),
    submitted_by_role    VARCHAR2(20 CHAR),
    submission_reason    VARCHAR2(1000 CHAR),
    assigned_to          VARCHAR2(64 CHAR),
    review_started_at    TIMESTAMP(6),
    escalated_by         VARCHAR2(64 CHAR),
    escalated_at         TIMESTAMP(6),
    escalation_reason    VARCHAR2(1000 CHAR),
    info_requested_by    VARCHAR2(64 CHAR),
    info_requested_at    TIMESTAMP(6),
    info_request_reason  VARCHAR2(1000 CHAR),
    resubmission_count   NUMBER(10),
    last_action_at       TIMESTAMP(6)
);

-- Replace the V2 status CHECK (PENDING, APPROVED, REJECTED, SUPERSEDED) with the extended lifecycle.
BEGIN
  FOR c IN (SELECT constraint_name FROM user_constraints
             WHERE table_name = 'RG_REFUND_REVIEWS' AND constraint_type = 'C'
               AND UPPER(search_condition_vc) LIKE '%SUPERSEDED%'
               AND UPPER(search_condition_vc) NOT LIKE '%MORE_INFO_REQUIRED%') LOOP
    EXECUTE IMMEDIATE 'ALTER TABLE rg_refund_reviews DROP CONSTRAINT ' || c.constraint_name;
  END LOOP;
END;
/

ALTER TABLE rg_refund_reviews ADD CONSTRAINT ck_rg_review_status CHECK (status IN
    ('PENDING','UNDER_REVIEW','MORE_INFO_REQUIRED','ESCALATED','APPROVED','REJECTED','SUPERSEDED'));

CREATE INDEX ix_rg_review_status ON rg_refund_reviews (status);

-- Rollback (loses the new case metadata; refunds stay ON_HOLD until decided):
--   UPDATE rg_refund_reviews SET status = 'PENDING' WHERE status IN ('UNDER_REVIEW','MORE_INFO_REQUIRED','ESCALATED');
--   ALTER TABLE rg_refund_reviews DROP CONSTRAINT ck_rg_review_status;
--   DROP INDEX ix_rg_review_status;
--   ALTER TABLE rg_refund_reviews DROP (origin, submitted_by, submitted_by_role, submission_reason, assigned_to,
--       review_started_at, escalated_by, escalated_at, escalation_reason, info_requested_by, info_requested_at,
--       info_request_reason, resubmission_count, last_action_at);
