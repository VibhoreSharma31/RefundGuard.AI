package com.refundguard.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PasswordPolicyTest {

    @Test
    void acceptsStrongPassword() {
        assertNull(PasswordPolicy.validate("admin", "Correct-horse-9-battery"));
    }

    @Test
    void rejectsShortNullAndBlank() {
        assertNotNull(PasswordPolicy.validate("u", null));
        assertNotNull(PasswordPolicy.validate("u", "short1"));
        assertNotNull(PasswordPolicy.validate("u", "            "));
    }

    @Test
    void rejectsPasswordContainingUsernameOrMissingClasses() {
        assertNotNull(PasswordPolicy.validate("operator", "Operator-123456"));
        assertNotNull(PasswordPolicy.validate("u", "onlyletterslongenough"));
        assertNotNull(PasswordPolicy.validate("u", "123456789012345"));
    }

    @Test
    void rejectsOverlongPassword() {
        assertNotNull(PasswordPolicy.validate("u", "a1".repeat(40)));
    }
}
