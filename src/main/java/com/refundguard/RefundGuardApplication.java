package com.refundguard;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.TimeZone;

@SpringBootApplication
@EnableScheduling
public class RefundGuardApplication {
    public static void main(String[] args) {
        // RBI TAT is counted in Indian calendar days; Docker containers default to UTC,
        // which would shift every "T+N" deadline by 5h30m.
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"));
        SpringApplication.run(RefundGuardApplication.class, args);
    }
}
