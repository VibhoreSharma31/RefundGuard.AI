package com.refundguard.risk;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Binds {@code refundguard.risk.*} (env: REFUNDGUARD_RISK_*) onto {@link RiskConfig}. */
@Component
@ConfigurationProperties(prefix = "refundguard.risk")
public class RiskSettings extends RiskConfig {
}
