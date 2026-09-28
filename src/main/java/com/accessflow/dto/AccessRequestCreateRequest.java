package com.accessflow.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of a new access request.
 *
 * There is no applicant field. The applicant is taken from the authenticated
 * caller, so a request body can never claim to be someone else.
 */
public record AccessRequestCreateRequest(

        @NotBlank(message = "application is required")
        @Size(max = 100, message = "application must not exceed 100 characters")
        String application,

        @NotBlank(message = "justification is required")
        @Size(max = 1000, message = "justification must not exceed 1000 characters")
        String justification
) {
}
