package com.pathwayai.backend.account;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps account failures to one JSON shape. Messages are fixed texts: a rejected value is never echoed back. */
@RestControllerAdvice(basePackageClasses = AccountController.class)
class AccountExceptionHandler {

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    record ErrorBody(int status, String code, String message, List<AccountException.Violation> violations) {
    }

    @ExceptionHandler(AccountException.class)
    ResponseEntity<ErrorBody> account(AccountException e) {
        return ResponseEntity.status(e.status()).header("Cache-Control", "no-store")
                .body(new ErrorBody(e.status(), e.code(), e.getMessage(), e.violations()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorBody> invalid(MethodArgumentNotValidException e) {
        List<AccountException.Violation> violations = e.getBindingResult().getFieldErrors().stream()
                .map(f -> new AccountException.Violation(f.getField(), f.getDefaultMessage() == null ? "Invalid value." : f.getDefaultMessage()))
                .toList();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).header("Cache-Control", "no-store")
                .body(new ErrorBody(400, "VALIDATION", "Some fields need attention.", violations));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorBody> unreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).header("Cache-Control", "no-store")
                .body(new ErrorBody(400, "VALIDATION", "The request could not be read.", List.of()));
    }
}
