package com.pathwayai.backend.account;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Set;

/**
 * Password rules in the spirit of NIST SP 800-63B: length matters, composition rules do not. At most 72 bytes because bcrypt ignores the
 * rest (silently truncating would be a trap), at least 10 characters, and not one of the passwords everybody tries first.
 */
final class PasswordPolicy {

    static final int MIN_LENGTH = 10;
    static final int MAX_BYTES = 72;

    private static final Set<String> COMMON = Set.of("password", "password1", "password123", "passw0rd123", "1234567890", "12345678910",
            "qwertyuiop", "qwerty12345", "1q2w3e4r5t", "iloveyou123", "letmein1234", "welcome1234", "admin12345", "abc1234567",
            "0123456789", "1111111111", "0000000000", "pathwayai", "pathway-ai", "studyabroad");

    private PasswordPolicy() {
    }

    /** @return the reason the password is unacceptable, or empty when it is fine */
    static Optional<String> violation(String password, String email) {
        if (password == null || password.length() < MIN_LENGTH) {
            return Optional.of("Use at least " + MIN_LENGTH + " characters.");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            return Optional.of("Use at most " + MAX_BYTES + " bytes (about 72 characters).");
        }
        String lower = password.toLowerCase();
        if (COMMON.contains(lower) || lower.chars().distinct().count() < 4) {
            return Optional.of("That password is too easy to guess. Choose a longer, less common one.");
        }
        if (email != null && email.contains("@")) {
            String local = email.toLowerCase().substring(0, email.indexOf('@'));
            if (lower.equals(email.toLowerCase()) || (local.length() >= 6 && lower.contains(local))) {
                return Optional.of("Do not use your email address in your password.");
            }
        }
        return Optional.empty();
    }
}
