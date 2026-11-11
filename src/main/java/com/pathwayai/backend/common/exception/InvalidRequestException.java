package com.pathwayai.backend.common.exception;

/** A request that is individually valid per parameter but inconsistent as a whole (maps to HTTP 400). */
public class InvalidRequestException extends RuntimeException {

    private final String field;

    public InvalidRequestException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String getField() {
        return field;
    }
}
