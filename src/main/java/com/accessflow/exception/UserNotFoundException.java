package com.accessflow.exception;

/**
 * Raised when a User cannot be located by the supplied identifier.
 *
 * Message format is fixed at "User not found: &lt;criteria&gt;" so callers and
 * logs can identify the failed lookup without a null-dependent NPE.
 */
public class UserNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public UserNotFoundException(String criteria) {
        super("User not found: " + criteria);
    }
}
