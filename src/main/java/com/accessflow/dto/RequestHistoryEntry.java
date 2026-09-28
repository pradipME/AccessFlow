package com.accessflow.dto;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

import com.accessflow.entity.AccessRequest;
import com.accessflow.entity.AccessRequestStage;
import com.accessflow.entity.User;

/**
 * One thing that happened to a request, oldest first.
 *
 * <h2>Derived, not stored</h2>
 *
 * There is no event table, and that is a consequence of the workflow rather than
 * an omission. Every fact in the history is written exactly once by exactly one
 * row: the submission is {@code created_at} on the request, each stage decision
 * is the decider, timestamp and notes on that stage, and a withdrawal is
 * {@code withdrawn_at} on the request. An append-only event table would be a
 * second source of truth for all of that, and a second write path to keep
 * consistent with the first.
 *
 * <h2>What Phase 5 changed, and what it did not</h2>
 *
 * Before Phase 5 a request had at most one terminal transition, so the history
 * was at most two entries read off the request row. There are now two approval
 * stages, so a request can have three entries before a withdrawal and a fourth
 * after one. The record, its components and the {@link Action} values are
 * unchanged: a second approval is another {@link Action#APPROVED}, distinguished
 * from the first by its actor, its timestamp and its notes. A client that
 * understood the Phase 4 contract still reads this one correctly - it now sees
 * more than one approval where it used to see one.
 *
 * What the derivation does depend on is that a fact is never written twice. A
 * decision that could be revised, or a stage that could be decided more than
 * once, would break it, and would have to bring the event table with it.
 *
 * <h2>Who may read it</h2>
 *
 * The same people who may read the request itself: the applicant, or a
 * reviewer. The applicant is summarised as a {@link Person}, so no password or
 * hash can be reached through the history.
 */
public record RequestHistoryEntry(
        Action action,
        Person actor,
        LocalDateTime at,
        String notes
) {

    public enum Action {
        SUBMITTED,
        APPROVED,
        REJECTED,
        WITHDRAWN
    }

    public record Person(Long id, String employeeId, String email, String firstName, String lastName) {
    }

    /**
     * Oldest first.
     *
     * Sorted rather than appended in a fixed order so the guarantee is real
     * rather than assumed: {@code created_at} is written by the database and the
     * decision timestamps by the application, and a clock that steps backwards
     * between them would otherwise put a later decision first. A stable sort also
     * means two entries sharing a timestamp keep the order they were added in -
     * submission before decisions, decisions in stage order.
     */
    private static final Comparator<RequestHistoryEntry> OLDEST_FIRST =
            Comparator.comparing(RequestHistoryEntry::at, Comparator.nullsLast(Comparator.naturalOrder()));

    /**
     * Reads the history off the request and its stages. Call it inside the
     * transaction that loaded the request: with open-in-view disabled, a lazy
     * association touched afterwards would fail rather than quietly return null.
     *
     * One entry for the submission, one for each stage that has been decided, and
     * one for a withdrawal. A request that is still PENDING has at least one entry
     * and never more than the number of stages plus one.
     */
    public static List<RequestHistoryEntry> of(AccessRequest request) {
        List<RequestHistoryEntry> entries = new java.util.ArrayList<>(request.getStages().size() + 2);

        entries.add(new RequestHistoryEntry(Action.SUBMITTED,
                toPerson(request.getApplicant()), request.getCreatedAt(), null));

        for (AccessRequestStage stage : request.getStages()) {
            if (!stage.isDecided()) {
                continue;
            }
            Action action = stage.getStatus() == AccessRequestStage.StageStatus.APPROVED
                    ? Action.APPROVED
                    : Action.REJECTED;

            entries.add(new RequestHistoryEntry(action,
                    toPerson(stage.getDecidedBy()), stage.getDecidedAt(), stage.getNotes()));
        }

        if (request.getWithdrawnAt() != null) {
            entries.add(new RequestHistoryEntry(Action.WITHDRAWN,
                    toPerson(request.getApplicant()), request.getWithdrawnAt(),
                    request.getWithdrawalReason()));
        }

        entries.sort(OLDEST_FIRST);

        return List.copyOf(entries);
    }

    private static Person toPerson(User user) {
        if (user == null) {
            return null;
        }
        return new Person(user.getId(), user.getEmployeeId(), user.getEmail(),
                user.getFirstName(), user.getLastName());
    }
}
