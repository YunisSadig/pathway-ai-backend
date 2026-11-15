package com.pathwayai.backend.account;

import java.util.List;

/** A business-rule failure with a stable machine-readable code and a message that is safe to show to the user. */
public class AccountException extends RuntimeException {

    /** {@code code} is a stable machine-readable reason so a client can show the message in the visitor's language. */
    public record Violation(String field, String message, String code) {
        public Violation(String field, String message) {
            this(field, message, "INVALID");
        }
    }

    private final int status;
    private final String code;
    private final List<Violation> violations;

    public AccountException(int status, String code, String message) {
        this(status, code, message, List.of());
    }

    public AccountException(int status, String code, String message, List<Violation> violations) {
        super(message);
        this.status = status;
        this.code = code;
        this.violations = violations;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public List<Violation> violations() {
        return violations;
    }
}
