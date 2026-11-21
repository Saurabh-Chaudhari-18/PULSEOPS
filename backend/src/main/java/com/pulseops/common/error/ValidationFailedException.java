package com.pulseops.common.error;

import java.util.List;

import org.springframework.http.HttpStatus;

/** 400 with field-level details (e.g. {@code events[3].timestamp}). */
public class ValidationFailedException extends ApiException {

    private final List<ApiError.FieldViolation> violations;

    public ValidationFailedException(String message, List<ApiError.FieldViolation> violations) {
        super(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message);
        this.violations = List.copyOf(violations);
    }

    public List<ApiError.FieldViolation> violations() {
        return violations;
    }
}
