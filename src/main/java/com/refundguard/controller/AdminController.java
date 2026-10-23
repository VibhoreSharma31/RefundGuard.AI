package com.refundguard.controller;

import com.refundguard.exception.ApiException;
import com.refundguard.model.AppUser;
import com.refundguard.repository.AppUserRepository;
import com.refundguard.risk.RiskScoringService;
import com.refundguard.security.*;
import com.refundguard.sla.SlaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/admin")
@Tag(name = "Administration", description = "User management and read-only view of the effective configuration")
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);
    private static final Pattern USERNAME = Pattern.compile("^[A-Za-z0-9._-]{3,64}$");

    public record CreateUserRequest(String username, String password, String role) {}
    public record UpdateUserRequest(String role, Boolean enabled, String password) {}

    private final AppUserRepository users;
    private final PasswordEncoder encoder;
    private final RiskScoringService riskService;
    private final SlaService slaService;
    private final boolean simulatorEnabled;

    public AdminController(AppUserRepository users, PasswordEncoder encoder, RiskScoringService riskService,
                           SlaService slaService, @Value("${refundguard.simulator.enabled:true}") boolean simulatorEnabled) {
        this.users = users;
        this.encoder = encoder;
        this.riskService = riskService;
        this.slaService = slaService;
        this.simulatorEnabled = simulatorEnabled;
    }

    @GetMapping("/users")
    @PreAuthorize("hasAuthority('USER_MANAGE')")
    @Operation(summary = "List users (never returns password hashes)")
    public ResponseEntity<List<Map<String, Object>>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (AppUser u : users.findAll()) out.add(view(u));
        out.sort(Comparator.comparing(m -> String.valueOf(m.get("username")).toLowerCase(Locale.ROOT)));
        return ResponseEntity.ok(out);
    }

    @PostMapping("/users")
    @PreAuthorize("hasAuthority('USER_MANAGE')")
    @Transactional
    @Operation(summary = "Create a user with a role")
    public ResponseEntity<Map<String, Object>> create(@RequestBody CreateUserRequest req) {
        if (req.username() == null || !USERNAME.matcher(req.username()).matches()) {
            throw ApiException.badRequest("Username must be 3-64 characters: letters, digits, dot, underscore or hyphen.");
        }
        Role role = parseRole(req.role());
        String err = PasswordPolicy.validate(req.username(), req.password());
        if (err != null) throw ApiException.badRequest(err);
        if (users.existsByUsernameIgnoreCase(req.username())) throw ApiException.conflict("Username already exists.");

        AppUser u = new AppUser();
        u.setUsername(req.username());
        u.setPasswordHash(encoder.encode(req.password()));
        u.setRole(role);
        u.setEnabled(true);
        u.setCreatedAt(LocalDateTime.now());
        u.setUpdatedAt(LocalDateTime.now());
        u = users.save(u);
        log.info("AUDIT user created: {} role={} by {}", u.getUsername(), role, CurrentUser.require().username());
        return ResponseEntity.status(HttpStatus.CREATED).body(view(u));
    }

    @PatchMapping("/users/{id}")
    @PreAuthorize("hasAuthority('USER_MANAGE')")
    @Transactional
    @Operation(summary = "Change a user's role, enabled flag or password")
    public ResponseEntity<Map<String, Object>> update(@PathVariable Long id, @RequestBody UpdateUserRequest req) {
        Actor actor = CurrentUser.require();
        AppUser u = users.findById(id).orElseThrow(() -> ApiException.notFound("User not found."));
        boolean self = u.getUsername().equalsIgnoreCase(actor.username());

        if (req.role() != null) {
            Role newRole = parseRole(req.role());
            if (self && newRole != u.getRole()) throw ApiException.conflict("You cannot change your own role.");
            if (u.getRole() == Role.ADMIN && newRole != Role.ADMIN) requireAnotherAdmin(u);
            u.setRole(newRole);
        }
        if (req.enabled() != null) {
            if (!req.enabled()) {
                if (self) throw ApiException.conflict("You cannot disable your own account.");
                if (u.getRole() == Role.ADMIN && u.isEnabled()) requireAnotherAdmin(u);
            }
            u.setEnabled(req.enabled());
        }
        if (req.password() != null) {
            String err = PasswordPolicy.validate(u.getUsername(), req.password());
            if (err != null) throw ApiException.badRequest(err);
            u.setPasswordHash(encoder.encode(req.password()));
        }
        u.setUpdatedAt(LocalDateTime.now());
        users.save(u);
        log.info("AUDIT user updated: {} (role={}, enabled={}, passwordReset={}) by {}", u.getUsername(), u.getRole(),
                u.isEnabled(), req.password() != null, actor.username());
        return ResponseEntity.ok(view(u));
    }

    @GetMapping("/config")
    @PreAuthorize("hasAuthority('SYSTEM_CONFIG_VIEW')")
    @Operation(summary = "Read-only effective configuration: risk rules, role-permission matrix, simulator flag (no secrets)")
    public ResponseEntity<Map<String, Object>> config() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("riskConfig", riskService.config());
        m.put("riskConfigFingerprint", riskService.config().fingerprint());
        m.put("simulatorEnabled", simulatorEnabled);
        m.put("slaPolicy", slaService.describePolicy());
        Map<String, List<String>> matrix = new LinkedHashMap<>();
        for (Role r : Role.values()) matrix.put(r.name(), RolePermissions.of(r).stream().map(Enum::name).sorted().toList());
        m.put("rolePermissions", matrix);
        m.put("note", "Risk and security settings are changed through environment variables / application properties and need a restart.");
        return ResponseEntity.ok(m);
    }

    private void requireAnotherAdmin(AppUser target) {
        long enabledAdmins = users.countByRoleAndEnabled(Role.ADMIN, Boolean.TRUE);
        boolean targetCounts = target.getRole() == Role.ADMIN && target.isEnabled();
        if (targetCounts && enabledAdmins <= 1) {
            throw ApiException.conflict("At least one enabled ADMIN must remain.");
        }
    }

    private static Role parseRole(String role) {
        if (role == null) throw ApiException.badRequest("role is required (OPERATOR, REVIEWER or ADMIN).");
        try {
            return Role.valueOf(role.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("role must be OPERATOR, REVIEWER or ADMIN.");
        }
    }

    private static Map<String, Object> view(AppUser u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", u.getId());
        m.put("username", u.getUsername());
        m.put("role", u.getRole());
        m.put("enabled", u.isEnabled());
        m.put("createdAt", u.getCreatedAt());
        m.put("updatedAt", u.getUpdatedAt());
        return m;
    }
}
