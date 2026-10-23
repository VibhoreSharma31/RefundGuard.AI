package com.refundguard.sla;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Turnaround-time (TAT) policy per payment method, bound from
 * {@code refundguard.sla.*} in application.properties.
 *
 * Example:
 *   refundguard.sla.tat-days.upi=1
 *   refundguard.sla.compensation-per-day.upi=100
 */
@Component
@ConfigurationProperties(prefix = "refundguard.sla")
public class SlaPolicyProperties {

    /** Calendar days allowed after the refund is initiated (T+N). */
    private Map<String, Integer> tatDays = new HashMap<>();

    /** Estimated compensation (INR) per day of delay beyond the deadline. */
    private Map<String, BigDecimal> compensationPerDay = new HashMap<>();

    private int defaultTatDays = 5;
    private BigDecimal defaultCompensationPerDay = new BigDecimal("100");

    public int tatDaysFor(String paymentMethod) {
        Integer v = normalized(tatDays).get(normalize(paymentMethod));
        return v != null ? v : defaultTatDays;
    }

    public BigDecimal compensationPerDayFor(String paymentMethod) {
        BigDecimal v = normalized(compensationPerDay).get(normalize(paymentMethod));
        return v != null ? v : defaultCompensationPerDay;
    }

    /** Policy table for the dashboard / API. */
    public Map<String, Map<String, Object>> describe() {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        normalized(tatDays).forEach((method, days) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("tatDays", days);
            row.put("label", "T+" + days);
            row.put("compensationPerDay", compensationPerDayFor(method));
            out.put(method, row);
        });
        Map<String, Object> def = new LinkedHashMap<>();
        def.put("tatDays", defaultTatDays);
        def.put("label", "T+" + defaultTatDays);
        def.put("compensationPerDay", defaultCompensationPerDay);
        out.put("DEFAULT", def);
        return out;
    }

    static String normalize(String method) {
        if (method == null) return "";
        return method.toUpperCase(Locale.ROOT).replaceAll("[^A-Z]", "");
    }

    private static <T> Map<String, T> normalized(Map<String, T> in) {
        Map<String, T> out = new LinkedHashMap<>();
        in.forEach((k, v) -> out.put(normalize(k), v));
        return out;
    }

    public Map<String, Integer> getTatDays() { return tatDays; }
    public void setTatDays(Map<String, Integer> tatDays) { this.tatDays = tatDays; }
    public Map<String, BigDecimal> getCompensationPerDay() { return compensationPerDay; }
    public void setCompensationPerDay(Map<String, BigDecimal> compensationPerDay) { this.compensationPerDay = compensationPerDay; }
    public int getDefaultTatDays() { return defaultTatDays; }
    public void setDefaultTatDays(int defaultTatDays) { this.defaultTatDays = defaultTatDays; }
    public BigDecimal getDefaultCompensationPerDay() { return defaultCompensationPerDay; }
    public void setDefaultCompensationPerDay(BigDecimal defaultCompensationPerDay) { this.defaultCompensationPerDay = defaultCompensationPerDay; }
}
