package com.pathwayai.backend.common.exception;

import com.pathwayai.backend.common.response.ApiError;
import com.pathwayai.backend.common.response.ApiError.FieldViolation;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ResourceNotFoundException.class)
    ResponseEntity<ApiError> notFound(ResourceNotFoundException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, ex.getMessage(), request, List.of());
    }

    @ExceptionHandler(InvalidRequestException.class)
    ResponseEntity<ApiError> invalidRequest(InvalidRequestException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "Invalid request parameters", request,
                List.of(new FieldViolation(ex.getField(), ex.getMessage())));
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    ResponseEntity<ApiError> invalidParameters(HandlerMethodValidationException ex, HttpServletRequest request) {
        List<FieldViolation> violations = ex.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> new FieldViolation(
                                result.getMethodParameter().getParameterName(), error.getDefaultMessage())))
                .toList();
        return build(HttpStatus.BAD_REQUEST, "Invalid request parameters", request, violations);
    }

    @ExceptionHandler(TypeMismatchException.class)
    ResponseEntity<ApiError> typeMismatch(TypeMismatchException ex, HttpServletRequest request) {
        String field = ex.getPropertyName() != null ? ex.getPropertyName() : "parameter";
        return build(HttpStatus.BAD_REQUEST, "Invalid request parameters", request,
                List.of(new FieldViolation(field, "must be a valid " + typeName(ex))));
    }

    /** Framework errors (404 for unknown paths, 405, missing params...) keep their own status; the rest is a 500. */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> fallback(Exception ex, HttpServletRequest request) {
        if (ex instanceof ErrorResponse errorResponse) {
            HttpStatusCode status = errorResponse.getStatusCode();
            return build(status, errorResponse.getBody().getDetail(), request, List.of());
        }
        log.error("Unhandled exception for {} {}", request.getMethod(), request.getRequestURI(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected server error", request, List.of());
    }

    private static String typeName(TypeMismatchException ex) {
        Class<?> type = ex.getRequiredType();
        return type == null ? "value" : type.getSimpleName().toLowerCase();
    }

    private static ResponseEntity<ApiError> build(HttpStatusCode status, String message,
                                                  HttpServletRequest request, List<FieldViolation> violations) {
        String reason = status instanceof HttpStatus http ? http.getReasonPhrase() : status.toString();
        ApiError body = new ApiError(Instant.now(), status.value(), reason, message,
                request.getRequestURI(), violations);
        return ResponseEntity.status(status).body(body);
    }
}
