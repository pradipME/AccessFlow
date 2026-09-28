package com.accessflow.dto;

import jakarta.validation.constraints.Size;

/**
 * Body of a withdraw action.
 *
 * A reason is optional. A rejection requires one because a reviewer owes the
 * applicant an explanation; an applicant taking back their own request owes
 * nobody one. It is still free text, so it carries the same length limit as
 * every other note in the system.
 *
 * There is no applicant, owner or id field, and that is the point: the only way
 * to name the person withdrawing is the authenticated caller, and nothing in
 * this record can be used to act on somebody else's request.
 */
public record AccessRequestWithdrawalRequest(

        @Size(max = 1000, message = "reason must not exceed 1000 characters")
        String reason
) {
}
