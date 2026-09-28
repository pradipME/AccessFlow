package com.accessflow.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of a reject action.
 *
 * A reason is mandatory. The applicant has to be told why their request was
 * turned down, and a rejection with no explanation is not actionable, so the
 * requirement lives on the DTO and is enforced at the API boundary as a 400.
 */
public record AccessRequestRejectionRequest(

        @NotBlank(message = "notes are required when rejecting an access request")
        @Size(max = 1000, message = "notes must not exceed 1000 characters")
        String notes
) {
}
