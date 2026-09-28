package com.accessflow.dto;

import java.time.LocalDateTime;

import com.accessflow.entity.AccessRequestStage;
import com.accessflow.entity.User;

/**
 * Outbound representation of one approval stage.
 *
 * A person appears as the {@link AccessRequestResponse.Person} summary rather
 * than a user object, so no password or hash can reach the response.
 *
 * Added in Phase 5. A stage that has not been decided has null decidedBy,
 * decidedAt and notes, so a client can tell "waiting for IT_ADMIN" from "approved
 * by IT_ADMIN" without inferring it from the request status.
 */
public record AccessRequestStageResponse(
        int order,
        User.Role requiredRole,
        AccessRequestStage.StageStatus status,
        AccessRequestResponse.Person decidedBy,
        LocalDateTime decidedAt,
        String notes
) {

    /**
     * Must be called inside the transaction that loaded the request: with
     * open-in-view disabled, the lazy decider association has to be resolved here
     * or not at all.
     */
    public static AccessRequestStageResponse from(AccessRequestStage stage) {
        return new AccessRequestStageResponse(
                stage.getStageOrder(),
                stage.getRequiredRole(),
                stage.getStatus(),
                AccessRequestResponse.toPerson(stage.getDecidedBy()),
                stage.getDecidedAt(),
                stage.getNotes()
        );
    }
}
