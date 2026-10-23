package com.refundguard.security;

/** Minimal password policy enforced for every password set through the application. */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 12;
    public static final int MAX_LENGTH = 72; // BCrypt only uses the first 72 bytes

    private PasswordPolicy() {}

    /** Returns an error message, or null if the password is acceptable. */
    public static String validate(String username, String password) {
        if (password == null || password.length() < MIN_LENGTH) return "Password must be at least " + MIN_LENGTH + " characters.";
        if (password.length() > MAX_LENGTH) return "Password must be at most " + MAX_LENGTH + " characters.";
        if (password.isBlank()) return "Password must not be blank.";
        if (username != null && !username.isBlank() && password.toLowerCase().contains(username.toLowerCase())) {
            return "Password must not contain the username.";
        }
        boolean letter = false, digit = false;
        for (char c : password.toCharArray()) {
            if (Character.isLetter(c)) letter = true;
            if (Character.isDigit(c)) digit = true;
        }
        if (!letter || !digit) return "Password must contain at least one letter and one digit.";
        return null;
    }
}
