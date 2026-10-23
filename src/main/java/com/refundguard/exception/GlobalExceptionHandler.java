package com.refundguard.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One consistent error shape: {"error":true,"status":<http>,"message":"..."}.
 * Security exceptions MUST map to 401/403 here (AccessDeniedException is a RuntimeException and would
 * otherwise be swallowed by the legacy RuntimeException -> 400 handler).
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> api(ApiException e) {
        return body(e.getStatus(), e.getMessage());
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> denied(AccessDeniedException e) {
        return body(HttpStatus.FORBIDDEN, "You do not have permission to perform this action.");
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Map<String, Object>> unauthenticated(AuthenticationException e) {
        return body(HttpStatus.UNAUTHORIZED, "Authentication required.");
    }

    @ExceptionHandler({ObjectOptimisticLockingFailureException.class, CannotAcquireLockException.class})
    public ResponseEntity<Map<String, Object>> concurrent(Exception e) {
        log.warn("Concurrent modification rejected: {}", e.getMessage());
        return body(HttpStatus.CONFLICT, "This record was changed by someone else at the same time. Reload and try again.");
    }

    /** Legacy behaviour of the original controller: unexpected runtime errors -> 400 with the message. */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, Object>> runtime(RuntimeException e) {
        return body(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    private static ResponseEntity<Map<String, Object>> body(HttpStatus status, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", true);
        m.put("status", status.value());
        m.put("message", message);
        return ResponseEntity.status(status).body(m);
    }
}
