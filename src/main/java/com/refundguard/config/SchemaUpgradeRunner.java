package com.refundguard.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;

/**
 * hibernate.ddl-auto=update adds new columns but never changes existing ones.
 * For databases created by the previous version this patches the columns whose
 * definition changed. Every statement is idempotent / failure-tolerant, and it
 * only runs on Oracle.
 */
@Component
public class SchemaUpgradeRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SchemaUpgradeRunner.class);

    private static final List<String> STATEMENTS = List.of(
            // UNMATCHED_SETTLEMENT cases have no refund record.
            "ALTER TABLE rg_cases MODIFY (refund_id NULL)",
            // AI text is longer than the old 255-char default.
            "ALTER TABLE rg_cases MODIFY (ai_root_cause_hypothesis VARCHAR2(4000))",
            "ALTER TABLE rg_cases MODIFY (recommended_action VARCHAR2(4000))",
            // Hibernate creates a CHECK constraint listing the enum values; older ones do not know SLA_BREACHED / ON_HOLD / REJECTED.
            """
            BEGIN
              FOR c IN (SELECT constraint_name FROM user_constraints
                         WHERE table_name = 'RG_REFUNDS' AND constraint_type = 'C'
                           AND UPPER(search_condition_vc) LIKE '%DERIVED_STATUS%IN%'
                           AND (UPPER(search_condition_vc) NOT LIKE '%SLA_BREACHED%'
                                OR UPPER(search_condition_vc) NOT LIKE '%ON_HOLD%'
                                OR UPPER(search_condition_vc) NOT LIKE '%REJECTED%')) LOOP
                EXECUTE IMMEDIATE 'ALTER TABLE rg_refunds DROP CONSTRAINT ' || c.constraint_name;
              END LOOP;
            END;
            """,
            // Review cases gained UNDER_REVIEW / MORE_INFO_REQUIRED / ESCALATED. Drop the old CHECK on
            // rg_refund_reviews.status (identified by SUPERSEDED, which only the review-status list contains).
            """
            BEGIN
              FOR c IN (SELECT constraint_name FROM user_constraints
                         WHERE table_name = 'RG_REFUND_REVIEWS' AND constraint_type = 'C'
                           AND UPPER(search_condition_vc) LIKE '%SUPERSEDED%'
                           AND UPPER(search_condition_vc) NOT LIKE '%MORE_INFO_REQUIRED%') LOOP
                EXECUTE IMMEDIATE 'ALTER TABLE rg_refund_reviews DROP CONSTRAINT ' || c.constraint_name;
              END LOOP;
            END;
            """
    );

    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;

    public SchemaUpgradeRunner(DataSource dataSource, JdbcTemplate jdbcTemplate) {
        this.dataSource = dataSource;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        try (Connection con = dataSource.getConnection()) {
            if (!con.getMetaData().getDatabaseProductName().toLowerCase().contains("oracle")) return;
        } catch (Exception e) {
            log.warn("Schema upgrade skipped: {}", e.getMessage());
            return;
        }
        for (String sql : STATEMENTS) {
            try {
                jdbcTemplate.execute(sql);
            } catch (Exception e) {
                // Expected when the column is already in the new shape (e.g. ORA-01451 "already NULL").
                log.debug("Schema upgrade statement skipped: {}", e.getMessage());
            }
        }
        log.info("Schema upgrade check complete");
    }
}
