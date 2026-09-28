package com.accessflow.exception;

/**
 * Thrown when login credentials are rejected.
 *
 * Deliberately does not extend UserNotFoundException: a failed authentication
 * is a 401, not a 404, and inheriting from a not-found exception would only
 * obscure that. The single message is intentionally vague so the response
 * cannot be used to discover which email addresses are registered.
 */
public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException() {
        super("Invalid email or password");
    }
}
