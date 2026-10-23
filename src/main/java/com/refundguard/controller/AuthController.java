package com.refundguard.controller;

import com.refundguard.exception.ApiException;
import com.refundguard.model.AppUser;
import com.refundguard.repository.AppUserRepository;
import com.refundguard.security.Actor;
import com.refundguard.security.CurrentUser;
import com.refundguard.security.PasswordPolicy;
import com.refundguard.security.RolePermissions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@Tag(name = "Authentication", description = "Who am I / change my password")
public class AuthController {

    public record ChangePasswordRequest(String currentPassword, String newPassword) {}

    private final AppUserRepository users;
    private final PasswordEncoder encoder;

    public AuthController(AppUserRepository users, PasswordEncoder encoder) {
        this.users = users;
        this.encoder = encoder;
    }

    @GetMapping("/me")
    @Operation(summary = "Identity, role and permissions of the authenticated caller (also validates credentials)")
    public ResponseEntity<Map<String, Object>> me() {
        Actor a = CurrentUser.require();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("username", a.username());
        m.put("role", a.role());
        List<String> perms = RolePermissions.of(a.role()).stream().map(Enum::name).sorted().toList();
        m.put("permissions", perms);
        return ResponseEntity.ok(m);
    }

    @PostMapping("/change-password")
    @Transactional
    @Operation(summary = "Change the caller's own password")
    public ResponseEntity<Map<String, Object>> changePassword(@RequestBody ChangePasswordRequest req) {
        Actor a = CurrentUser.require();
        AppUser u = users.findByUsernameIgnoreCase(a.username()).orElseThrow(() -> ApiException.notFound("User not found."));
        if (req.currentPassword() == null || !encoder.matches(req.currentPassword(), u.getPasswordHash())) {
            throw ApiException.badRequest("Current password is incorrect.");
        }
        String err = PasswordPolicy.validate(u.getUsername(), req.newPassword());
        if (err != null) throw ApiException.badRequest(err);
        if (encoder.matches(req.newPassword(), u.getPasswordHash())) {
            throw ApiException.badRequest("New password must differ from the current one.");
        }
        u.setPasswordHash(encoder.encode(req.newPassword()));
        u.setUpdatedAt(LocalDateTime.now());
        users.save(u);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("message", "Password changed. Sign in again with the new password.");
        return ResponseEntity.ok(m);
    }
}
