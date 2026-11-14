package com.pathwayai.backend.common.exception;

public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String resource, String identifier) {
        super(resource + " not found: " + identifier);
    }

    /** A resource that exists but is deliberately not shown; the message explains why (still a 404 to the client). */
    protected ResourceNotFoundException(String message) {
        super(message);
    }
}
