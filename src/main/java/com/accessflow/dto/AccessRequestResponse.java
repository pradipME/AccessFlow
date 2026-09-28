package com.accessflow.dto;

import java.time.LocalDateTime;
import java.util.List;

import com.accessflow.entity.AccessRequest;
import com.accessflow.entity.User;

/**
 * Outbound representation of an access request.
 *
 * People appear as a nested {@link Person} summary rather than as a full user
 * object, so no password or hash can reach the response even if a future refactor
 * widens what the entity exposes.
 *
 * The two withdrawal fields were added in Phase 4 and are nullable, so every
 * pre-Phase-4 request serialises with them absent and no existing consumer sees
 * a changed field name or type. They are not optional for a caller to
 * understand: without them a withdrawn request would be indistinguishable from
 * one still waiting for a reviewer.
 *
 * The {@code stages} list was added in Phase 5 and is appended last for the same
 * reason: it is new, it is never null, and no existing field changed name, type
 * or position. Only {@link #from} builds this record, so appending a component
 * cannot silently misalign an argument somewhere else.
 */
public record AccessRequestResponse(
        Long id,
        String application,
        String justification,
        AccessRequest.Status status,
        Person applicant,
        Person reviewedBy,
        LocalDateTime reviewedAt,
        String reviewNotes,
        LocalDateTime withdrawnAt,
        String withdrawalReason,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        List<AccessRequestStageResponse> stages
) {

    public record Person(Long id, String employeeId, String email, String firstName, String lastName) {
    }

    public static AccessRequestResponse from(AccessRequest request) {
        return new AccessRequestResponse(
                request.getId(),
                request.getApplication(),
                request.getJustification(),
                request.getStatus(),
                toPerson(request.getApplicant()),
                toPerson(request.getReviewedBy()),
                request.getReviewedAt(),
                request.getReviewNotes(),
                request.getWithdrawnAt(),
                request.getWithdrawalReason(),
                request.getCreatedAt(),
                request.getUpdatedAt(),
                // Mapped here rather than exposed as entities, so the stage rows
                // never cross the service boundary and a caller cannot mutate a
                // workflow in place. Sorted by the mapping, not only by the
                // entity's @OrderBy, so the order the API promises does not
                // depend on a fetch strategy.
                request.getStages().stream()
                        .sorted(java.util.Comparator.comparingInt(
                                com.accessflow.entity.AccessRequestStage::getStageOrder))
                        .map(AccessRequestStageResponse::from)
                        .toList()
        );
    }

    /**
     * The stage a decision would apply to right now, or null if there is none.
     *
     * The first stage still PENDING, and only while the request itself is
     * PENDING: a resolved request has no outstanding stage, so its later stages
     * read as unfinished but unreachable. Answering null rather than the first
     * PENDING row unconditionally is what stops a rejected request being
     * presented as still awaiting somebody.
     *
     * Defined once here so the pages and anything else reading a response agree
     * on which stage is current. Not a record component, so it is derived on
     * demand and adds nothing to the serialised body.
     */
    public AccessRequestStageResponse nextStage() {
        if (status != AccessRequest.Status.PENDING || stages == null) {
            return null;
        }
        return stages.stream()
                .filter(stage -> stage.status() == com.accessflow.entity.AccessRequestStage.StageStatus.PENDING)
                .findFirst()
                .orElse(null);
    }

    /**
     * Must be called inside the transaction that loaded the request: with
     * open-in-view disabled, the lazy associations have to be resolved here or
     * not at all.
     *
     * Package-private rather than private because AccessRequestStageResponse
     * reuses it, so both mappers summarise a person in exactly one way.
     */
    static Person toPerson(User user) {
        if (user == null) {
            return null;
        }
        return new Person(user.getId(), user.getEmployeeId(), user.getEmail(),
                user.getFirstName(), user.getLastName());
    }
}
