package com.accessflow.exception;

/**
 * Thrown when a user tries to review their own access request.
 *
 * Separation of duties: if the applicant could approve their own request the
 * approval step would mean nothing, so this is refused even for a SUPER_ADMIN.
 */
public class SelfApprovalNotAllowedException extends RuntimeException {

    public SelfApprovalNotAllowedException() {
        super("You cannot review your own access request");
    }
}
