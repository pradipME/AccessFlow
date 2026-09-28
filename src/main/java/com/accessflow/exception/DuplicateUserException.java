package com.accessflow.exception;

/**
 * Raised when a create would breach the uniqueness of employee_id or email.
 *
 * The offending field name and value are carried separately so a future
 * controller can map the violation to the correct form field without parsing
 * the message text.
 */
public class DuplicateUserException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String field;
    private final String value;

    public DuplicateUserException(String field, String value) {
        super("A user with this " + field + " already exists: " + value);
        this.field = field;
        this.value = value;
    }

    public String getField() {
        return field;
    }

    public String getValue() {
        return value;
    }
}
