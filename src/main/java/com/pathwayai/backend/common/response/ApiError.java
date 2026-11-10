package com.pathwayai.backend.common.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

/** Uniform error body returned for every non-2xx API response. */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ApiError(Instant timestamp, int status, String error, String message, String path,
                       List<FieldViolation> violations) {

    public record FieldViolation(String field, String message) {
    }
}
