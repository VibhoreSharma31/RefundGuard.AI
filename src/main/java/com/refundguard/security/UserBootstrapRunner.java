package com.refundguard.security;

import com.refundguard.model.AppUser;
import com.refundguard.repository.AppUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.LocalDateTime;

/**
 * Creates the first accounts when the user table is empty. No default password exists in the code:
 *  - RG_ADMIN_PASSWORD   -> admin account (if unset, a random password is generated and logged ONCE at WARN level)
 *  - RG_OPERATOR_PASSWORD / RG_REVIEWER_PASSWORD -> optional demo accounts, created only when provided
 */
@Component
public class UserBootstrapRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(UserBootstrapRunner.class);
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";

    private final AppUserRepository users;
    private final PasswordEncoder encoder;
    private final String adminUsername;
    private final String adminPassword;
    private final String operatorPassword;
    private final String reviewerPassword;

    public UserBootstrapRunner(AppUserRepository users, PasswordEncoder encoder,
                               @Value("${refundguard.security.bootstrap.admin-username:admin}") String adminUsername,
                               @Value("${refundguard.security.bootstrap.admin-password:}") String adminPassword,
                               @Value("${refundguard.security.bootstrap.operator-password:}") String operatorPassword,
                               @Value("${refundguard.security.bootstrap.reviewer-password:}") String reviewerPassword) {
        this.users = users;
        this.encoder = encoder;
        this.adminUsername = adminUsername;
        this.adminPassword = adminPassword;
        this.operatorPassword = operatorPassword;
        this.reviewerPassword = reviewerPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (users.count() > 0) return;

        String pw = adminPassword;
        boolean generated = pw == null || pw.isBlank();
        if (generated) pw = randomPassword();
        create(adminUsername, pw, Role.ADMIN);
        if (generated) {
            log.warn("==========================================================================");
            log.warn("No RG_ADMIN_PASSWORD set. Generated one-time admin credentials:");
            log.warn("    username: {}   password: {}", adminUsername, pw);
            log.warn("Sign in and change it now (POST /api/auth/change-password). It is not shown again.");
            log.warn("==========================================================================");
        }
        if (operatorPassword != null && !operatorPassword.isBlank()) create("operator", operatorPassword, Role.OPERATOR);
        if (reviewerPassword != null && !reviewerPassword.isBlank()) create("reviewer", reviewerPassword, Role.REVIEWER);
        log.info("Bootstrapped {} user account(s)", users.count());
    }

    private void create(String username, String password, Role role) {
        String err = PasswordPolicy.validate(username, password);
        if (err != null) throw new IllegalStateException("Refusing to create bootstrap user '" + username + "': " + err);
        AppUser u = new AppUser();
        u.setUsername(username);
        u.setPasswordHash(encoder.encode(password));
        u.setRole(role);
        u.setEnabled(true);
        u.setCreatedAt(LocalDateTime.now());
        u.setUpdatedAt(LocalDateTime.now());
        users.save(u);
    }

    private static String randomPassword() {
        SecureRandom r = new SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 20; i++) sb.append(ALPHABET.charAt(r.nextInt(ALPHABET.length())));
        sb.append(r.nextInt(10)); // guarantees a digit
        return sb.toString();
    }
}
