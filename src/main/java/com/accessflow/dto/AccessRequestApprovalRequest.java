package com.accessflow.dto;

import jakarta.validation.constraints.Size;

/**
 * Body of an approve action. Notes are optional: a reviewer may approve without
 * comment, but a rejection always requires a reason (see the rejection DTO).
 */
public record AccessRequestApprovalRequest(

        @Size(max = 1000, message = "notes must not exceed 1000 characters")
        String notes
) {
}
