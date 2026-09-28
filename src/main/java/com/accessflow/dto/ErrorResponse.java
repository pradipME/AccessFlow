package com.accessflow.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Uniform error body for every failure returned by the API.
 *
 * Never carries a stack trace, a password, or a credential. The field/value
 * pair from DuplicateUserException is surfaced as a validation-style list so a
 * client can bind the conflict to a form field.
 */
public record ErrorResponse(
        LocalDateTime timestamp,
        int status,
        String error,
        String message,
        String path,
        List<FieldError> validationErrors
) {

    public static ErrorResponse of(int status, String error, String message, String path) {
        return new ErrorResponse(LocalDateTime.now(), status, error, message, path, List.of());
    }

    public static ErrorResponse of(int status, String error, String message, String path,
                                   List<FieldError> validationErrors) {
        return new ErrorResponse(LocalDateTime.now(), status, error, message, path, validationErrors);
    }

    public record FieldError(String field, String message) {
    }
}
