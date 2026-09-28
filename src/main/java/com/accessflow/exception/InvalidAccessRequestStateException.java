package com.accessflow.exception;

import com.accessflow.entity.AccessRequest;

/**
 * Thrown when a workflow transition is not legal from the request's current
 * status, for example approving a request that was already rejected.
 *
 * A conflict rather than a bad request: the request itself was well formed, it
 * simply cannot move from where it is.
 */
public class InvalidAccessRequestStateException extends RuntimeException {

    public InvalidAccessRequestStateException(AccessRequest.Status current, String attempted) {
        super("Cannot " + attempted + " an access request with status " + current);
    }
}
