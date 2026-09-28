package com.accessflow.exception;

import com.accessflow.entity.User;

/**
 * Thrown when a signed-in user tries an operation that their role does not
 * allow, for example an employee attempting to review an access request.
 *
 * Enforced in the service as well as in SecurityConfig. The filter chain is the
 * first gate, but a rule that only lives in configuration stops protecting the
 * system the moment anything calls the service without going through HTTP.
 */
public class InsufficientRoleException extends RuntimeException {

    public InsufficientRoleException(User.Role role, String operation) {
        super("Role " + role + " is not permitted to " + operation);
    }
}
