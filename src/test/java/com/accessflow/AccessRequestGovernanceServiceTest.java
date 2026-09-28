package com.accessflow;

import java.util.ArrayList;
import java.util.List;

import com.accessflow.dto.AccessRequestResponse;
import com.accessflow.dto.PagedResponse;
import com.accessflow.dto.RequestHistoryEntry;
import com.accessflow.entity.AccessRequest;
import com.accessflow.entity.User;
import com.accessflow.exception.AccessRequestNotFoundException;
import com.accessflow.exception.InsufficientRoleException;
import com.accessflow.exception.InvalidAccessRequestStateException;
import com.accessflow.exception.UserNotFoundException;
import com.accessflow.service.AccessRequestService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Phase 4 - the service rules behind withdrawal, history and paging.
 *
 * These go through the service rather than the HTTP layer, so a rule is pinned
 * down where it is implemented. The endpoint tests then cover what the transport
 * adds: status codes, validation and the JSON shape.
 */
@DisplayName("Phase 4 - AccessRequestService withdrawal, history and paging")
@EnabledIfEnvironmentVariable(named = "ACCESSFLOW_DB_PASSWORD", matches = ".+")
class AccessRequestGovernanceServiceTest extends IntegrationTestSupport {

    @Test
    @DisplayName("Withdrawals require no new exception types; they reuse the existing three")
    void withdrawalReusesTheExistingExceptions() {
        Long employeeId = idOf(EMPLOYEE_EMAIL);
        Long otherEmployeeId = createUser("EMP-2ND", "actor.employee2@accessflow.local",
                User.Role.EMPLOYEE);
        Long id = createAccessRequest(employeeId, "AWS", "take it back");

        // 404 for a request that does not exist.
        assertThatThrownBy(() -> accessRequestService.withdrawRequest(9_999_999L, employeeId, null))
                .isInstanceOf(AccessRequestNotFoundException.class);

        // 403 for somebody else's, using the same exception the role checks use.
        assertThatThrownBy(() -> accessRequestService.withdrawRequest(id, otherEmployeeId, null))
                .isInstanceOf(InsufficientRoleException.class);

        // 409 for one that is already resolved, using the same exception a second
        // review raises. A withdrawal is just another transition.
        accessRequestService.withdrawRequest(id, employeeId, "first");
        assertThatThrownBy(() -> accessRequestService.withdrawRequest(id, employeeId, "second"))
                .isInstanceOf(InvalidAccessRequestStateException.class);
    }

    @Test
    @DisplayName("Withdrawal records the applicant as the actor and leaves the reviewer fields null")
    void withdrawalLeavesTheReviewerFieldsAlone() {
        Long employeeId = idOf(EMPLOYEE_EMAIL);
        Long id = createAccessRequest(employeeId, "GitHub", "no longer needed");

        AccessRequestResponse withdrawn = accessRequestService
                .withdrawRequest(id, employeeId, "project postponed");

        assertThat(withdrawn.status()).isEqualTo(AccessRequest.Status.WITHDRAWN);
        assertThat(withdrawn.withdrawnAt()).isNotNull();
        assertThat(withdrawn.withdrawalReason()).isEqualTo("project postponed");
        assertThat(withdrawn.reviewedBy()).isNull();
        assertThat(withdrawn.reviewedAt()).isNull();
        assertThat(withdrawn.reviewNotes()).isNull();
    }

    @Test
    @DisplayName("Withdrawal works for every role, because the rule is about ownership and not authority")
    void everyRoleCanWithdrawItsOwnRequest() {
        Long[] applicantIds = {
                idOf(EMPLOYEE_EMAIL), idOf(MANAGER_EMAIL),
                idOf(IT_ADMIN_EMAIL), idOf(SUPER_ADMIN_EMAIL)
        };

        for (int i = 0; i < applicantIds.length; i++) {
            Long id = createAccessRequest(applicantIds[i], "App" + i, "mine to withdraw");
            assertThat(accessRequestService.withdrawRequest(id, applicantIds[i], "changed my mind").status())
                    .isEqualTo(AccessRequest.Status.WITHDRAWN);
        }
    }

    @Test
    @DisplayName("No role may withdraw another applicant's request")
    void noRoleMayWithdrawAnotherRequest() {
        Long employeeId = idOf(EMPLOYEE_EMAIL);
        Long id = createAccessRequest(employeeId, "AWS", "applicant's request");

        // A reviewer already has approve and reject. A note-free way for a
        // reviewer to close another person's request would leave the applicant
        // nothing to read and the reviewer nothing to write.
        for (Long reviewerId : new Long[]{idOf(MANAGER_EMAIL), idOf(IT_ADMIN_EMAIL),
                idOf(SUPER_ADMIN_EMAIL)}) {
            assertThatThrownBy(() -> accessRequestService.withdrawRequest(id, reviewerId, "not mine"))
                    .isInstanceOf(InsufficientRoleException.class);
        }

        assertThat(accessRequestService.getRequestById(id, employeeId).status())
                .isEqualTo(AccessRequest.Status.PENDING);
    }

    @Test
    @DisplayName("Ownership is checked before the state, so a non-owner learns nothing about a resolved request")
    void ownershipIsCheckedBeforeState() {
        // The order is 404, 403, 409. Reversing the last two would let a
        // non-owner discover whether a request is still open by watching for a
        // 409 rather than a 403, which is the one thing they should not learn.
        Long employeeId = idOf(EMPLOYEE_EMAIL);
        Long otherEmployeeId = createUser("EMP-2ND", "actor.employee2@accessflow.local",
                User.Role.EMPLOYEE);
        Long id = createAccessRequest(employeeId, "AWS", "already decided");
        approveEveryStage(id);

        // The request is APPROVED, so a 409 would be the answer for its owner.
        assertThatThrownBy(() -> accessRequestService.withdrawRequest(id, employeeId, null))
                .isInstanceOf(InvalidAccessRequestStateException.class);

        // The non-owner gets the refusal about ownership, not about the state.
        assertThatThrownBy(() -> accessRequestService.withdrawRequest(id, otherEmployeeId, null))
                .isInstanceOf(InsufficientRoleException.class);
    }

    @Test
    @DisplayName("Existence is checked before ownership, so an unknown id is a 404 for everybody")
    void existenceIsCheckedBeforeOwnership() {
        Long otherEmployeeId = createUser("EMP-2ND", "actor.employee2@accessflow.local",
                User.Role.EMPLOYEE);

        assertThatThrownBy(() -> accessRequestService.withdrawRequest(9_999_999L, otherEmployeeId, null))
                .isInstanceOf(AccessRequestNotFoundException.class);
    }

    @Test
    @DisplayName("A withdrawal reason of null and an empty string are both allowed")
    void reasonIsOptional() {
        Long employeeId = idOf(EMPLOYEE_EMAIL);
        Long withoutReason = createAccessRequest(employeeId, "AWS", "no explanation offered");
        Long emptyReason = createAccessRequest(employeeId, "Jira", "empty explanation");

        assertThat(accessRequestService.withdrawRequest(withoutReason, employeeId, null)
                .withdrawalReason()).isNull();
        assertThat(accessRequestService.withdrawRequest(emptyReason, employeeId, "")
                .withdrawalReason()).isEmpty();
    }

    @Test
    @DisplayName("WITHDRAWN is terminal in every direction, like APPROVED and REJECTED")
    void withdrawnIsTerminal() {
        Long employeeId = idOf(EMPLOYEE_EMAIL);
        Long reviewerId = idOf(MANAGER_EMAIL);
        Long id = createAccessRequest(employeeId, "AWS", "resolve one way");
        accessRequestService.withdrawRequest(id, employeeId, null);

        assertThatThrownBy(() -> accessRequestService.approveRequest(id, reviewerId, "late"))
                .isInstanceOf(InvalidAccessRequestStateException.class)
                .hasMessageContaining("WITHDRAWN");

        assertThatThrownBy(() -> accessRequestService.rejectRequest(id, reviewerId, "late"))
                .isInstanceOf(InvalidAccessRequestStateException.class);

        assertThatThrownBy(() -> accessRequestService.withdrawRequest(id, employeeId, "again"))
                .isInstanceOf(InvalidAccessRequestStateException.class);
    }

    @Test
    @DisplayName("A resolved request cannot be withdrawn, whichever way it was resolved")
    void resolvedRequestsCannotBeWithdrawn() {
        Long employeeId = idOf(EMPLOYEE_EMAIL);
        Long reviewerId = idOf(MANAGER_EMAIL);

        Long approved = createAccessRequest(employeeId, "AWS", "approved");
        approveEveryStage(approved);
        assertThatThrownBy(() -> accessRequestService.withdrawRequest(approved, employeeId, "changed my mind"))
                .isInstanceOf(InvalidAccessRequestStateException.class)
                .hasMessageContaining("APPROVED");

        Long rejected = createAccessRequest(employeeId, "HRMS", "rejected");
        accessRequestService.rejectRequest(rejected, reviewerId, "no need");
        assertThatThrownBy(() -> accessRequestService.withdrawRequest(rejected, employeeId, "changed my mind"))
                .isInstanceOf(InvalidAccessRequestStateException.class)
                .hasMessageContaining("REJECTED");
    }

    @Test
    @DisplayName("A refused withdrawal changes nothing")
    void refusedWithdrawalLeavesTheRequestUntouched() {
        Long employeeId = idOf(EMPLOYEE_EMAIL);
        Long id = createAccessRequest(employeeId, "AWS", "resolve me");
        approveEveryStage(id);

        assertThatThrownBy(() -> accessRequestService.withdrawRequest(id, employeeId, "too late"))
                .isInstanceOf(InvalidAccessRequestStateException.class);

        AccessRequestResponse unchanged = accessRequestService.getRequestById(id, employeeId);
        assertThat(unchanged.status()).isEqualTo(AccessRequest.Status.APPROVED);
        assertThat(unchanged.withdrawnAt()).isNull();
        assertThat(unchanged.withdrawalReason()).isNull();
        assertThat(unchanged.reviewNotes()).isEqualTo("stage 2 ok");
    }

    @Test
    @DisplayName("A withdrawal survives a re-read, so it was persisted and not only held in memory")
    void withdrawalIsPersisted() {
        Long employeeId = idOf(EMPLOYEE_EMAIL);
        Long id = createAccessRequest(employeeId, "AWS", "persist me");
        accessRequestService.withdrawRequest(id, employeeId, "changed my mind");

        AccessRequestResponse reread = accessRequestService.getRequestById(id, employeeId);
        assertThat(reread.status()).isEqualTo(AccessRequest.Status.WITHDRAWN);
        assertThat(reread.withdrawnAt()).isNotNull();
        assertThat(reread.withdrawalReason()).isEqualTo("changed my mind");
    }

    @Test
    @DisplayName("A withdrawn request still appears in its applicant's own list")
    void withdrawnRequestsStayInTheApplicantsList() {
        Long employeeId = idOf(EMPLOYEE_EMAIL);
        Long id = createAccessRequest(employeeId, "AWS", "still mine");
        accessRequestService.withdrawRequest(id, employeeId, "changed my mind");

        assertThat(accessRequestService.getRequestsByApplicant(employeeId))
                .hasSize(1)
                .extracting(AccessRequestResponse::id, AccessRequestResponse::status)
                .containsExactly(tuple(id, AccessRequest.Status.WITHDRAWN));
    }

    @Test
    @DisplayName("WITHDRAWN appears in the status listings alongside the original three")
    void withdrawnAppearsInStatusListings() {
        Long employeeId = idOf(EMPLOYEE_EMAIL);
        Long id = createAccessRequest(employeeId, "AWS", "take it back");
        accessRequestService.withdrawRequest(id, employeeId, null);

        assertThat(accessRequestService.getRequestsByStatus(AccessRequest.Status.WITHDRAWN))
                .extracting(AccessRequestResponse::id).contains(id);

        assertThat(accessRequestService.getRequestsByStatus(AccessRequest.Status.PENDING))
                .extracting(AccessRequestResponse::id).doesNotContain(id);
    }

    @Test
    @DisplayName("A pending request's history is the submission alone")
    void pendingHistory() {
        Long employeeId = idOf(EMPLOYEE_EMAIL);
        Long id = createAccessRequest(employeeId, "GitHub", "need access");

        assertThat(accessRequestService.getRequestHistory(id, employeeId))
                .hasSize(1)
                .extracting(RequestHistoryEntry::action)
                .containsExactly(RequestHistoryEntry.Action.SUBMITTED);
    }

    @Test
    @DisplayName("A fully approved request's history is the submission then both approvals, oldest first")
    void approvedHistory() {
        // Both stages are in the history now, and the request's own reviewedAt is
        // no longer the source of the APPROVED entries: each one is read off the
        // stage that recorded it. The final entry's actor is the same person the
        // request's reviewedBy names, so the two views still agree.
        Long employeeId = idOf(EMPLOYEE_EMAIL);
        Long managerId = idOf(MANAGER_EMAIL);
        Long itAdminId = idOf(IT_ADMIN_EMAIL);
        Long id = createAccessRequest(employeeId, "AWS", "quarter end");
        approveEveryStage(id);

        assertThat(accessRequestService.getRequestHistory(id, employeeId))
                .hasSize(3)
                .extracting(RequestHistoryEntry::action)
                .containsExactly(RequestHistoryEntry.Action.SUBMITTED,
                        RequestHistoryEntry.Action.APPROVED,
                        RequestHistoryEntry.Action.APPROVED);

        List<RequestHistoryEntry> history = accessRequestService.getRequestHistory(id, employeeId);

        assertThat(history.get(1).actor().id()).isEqualTo(managerId);
        assertThat(history.get(1).actor().email()).isEqualTo(MANAGER_EMAIL);
        assertThat(history.get(1).notes()).isEqualTo("stage 1 ok");
        assertThat(history.get(1).at()).isNotNull();

        assertThat(history.get(2).actor().id()).isEqualTo(itAdminId);
        assertThat(history.get(2).notes()).isEqualTo("stage 2 ok");

        // The decider of the last stage is the reviewer the request records.
        assertThat(accessRequestService.getRequestById(id, employeeId).reviewedBy().id())
                .isEqualTo(itAdminId);
    }

    @Test
    @DisplayName("A request between the two stages records the first approval and is still pending")
    void partApprovedHistory() {
        Long employeeId = idOf(EMPLOYEE_EMAIL);
        Long managerId = idOf(MANAGER_EMAIL);
        Long id = createAccessRequest(employeeId, "AWS", "halfway");
        approveFirstStageOnly(id);

        assertThat(accessRequestService.getRequestHistory(id, employeeId))
                .extracting(RequestHistoryEntry::action)
                .containsExactly(RequestHistoryEntry.Action.SUBMITTED,
                        RequestHistoryEntry.Action.APPROVED);

        assertThat(accessRequestService.getRequestById(id, employeeId).status())
                .isEqualTo(AccessRequest.Status.PENDING);
    }

    @Test
    @DisplayName("A rejected request's history records the rejection and its reason")
    void rejectedHistory() {
        // A MANAGER rejecting at stage 1 terminates the request, so there is no
        // second decision in the history.
        Long employeeId = idOf(EMPLOYEE_EMAIL);
        Long reviewerId = idOf(MANAGER_EMAIL);
        Long id = createAccessRequest(employeeId, "HRMS", "payroll");
        accessRequestService.rejectRequest(id, reviewerId, "not your department");

        assertThat(accessRequestService.getRequestHistory(id, employeeId))
                .extracting(RequestHistoryEntry::action)
                .containsExactly(RequestHistoryEntry.Action.SUBMITTED,
                        RequestHistoryEntry.Action.REJECTED);

        assertThat(accessRequestService.getRequestHistory(id, employeeId).get(1).notes())
                .isEqualTo("not your department");
    }

    @Test
    @DisplayName("A rejection at the second stage terminates the request and records both the approval and the rejection")
    void rejectedAtSecondStageHistory() {
        // Stage 1 approved, then stage 2 rejected. The request is REJECTED and the
        // history holds all three, which is the case Phase 5's derivation exists
        // for and the one a single-decision history could not express.
        Long employeeId = idOf(EMPLOYEE_EMAIL);
        Long id = createAccessRequest(employeeId, "AWS", "approved then refused");
        approveFirstStageOnly(id);
        accessRequestService.rejectRequest(id, idOf(IT_ADMIN_EMAIL), "policy blocks this");

        assertThat(accessRequestService.getRequestHistory(id, employeeId))
                .extracting(RequestHistoryEntry::action)
                .containsExactly(RequestHistoryEntry.Action.SUBMITTED,
                        RequestHistoryEntry.Action.APPROVED,
                        RequestHistoryEntry.Action.REJECTED);

        AccessRequestResponse rejected = accessRequestService.getRequestById(id, employeeId);
        assertThat(rejected.status()).isEqualTo(AccessRequest.Status.REJECTED);
        assertThat(rejected.reviewedBy().id()).isEqualTo(idOf(IT_ADMIN_EMAIL));
        assertThat(rejected.nextStage()).isNull();
    }

    @Test
    @DisplayName("A withdrawn request's history records the withdrawal, by the applicant")
    void withdrawnHistory() {
        Long employeeId = idOf(EMPLOYEE_EMAIL);
        Long id = createAccessRequest(employeeId, "Jira", "changed my mind");
        accessRequestService.withdrawRequest(id, employeeId, "project postponed");

        RequestHistoryEntry withdrawal = accessRequestService
                .getRequestHistory(id, employeeId).get(1);

        assertThat(withdrawal.action()).isEqualTo(RequestHistoryEntry.Action.WITHDRAWN);
        // The applicant is the actor: they are the only party who may withdraw.
        assertThat(withdrawal.actor().id()).isEqualTo(employeeId);
        assertThat(withdrawal.notes()).isEqualTo("project postponed");
    }

    @Test
    @DisplayName("Entries are ordered by when they happened, not by the order they are discovered")
    void historyIsOrderedByTime() {
        Long employeeId = idOf(EMPLOYEE_EMAIL);
        Long id = createAccessRequest(employeeId, "AWS", "ordered");
        approveEveryStage(id);

        List<RequestHistoryEntry> history = accessRequestService.getRequestHistory(id, employeeId);

        assertThat(history).extracting(RequestHistoryEntry::at)
                .isSorted();
    }

    @Test
    @DisplayName("The history is the same for the applicant and for a reviewer")
    void historyDoesNotDependOnTheReader() {
        Long employeeId = idOf(EMPLOYEE_EMAIL);
        Long id = createAccessRequest(employeeId, "AWS", "same for all");
        accessRequestService.withdrawRequest(id, employeeId, "changed my mind");

        assertThat(accessRequestService.getRequestHistory(id, idOf(SUPER_ADMIN_EMAIL)))
                .isEqualTo(accessRequestService.getRequestHistory(id, employeeId));
    }

    @Test
    @DisplayName("A history is refused to another employee, through the same rule as the request")
    void historyObeysTheReadRule() {
        Long employeeId = idOf(EMPLOYEE_EMAIL);
        Long otherEmployeeId = createUser("EMP-2ND", "actor.employee2@accessflow.local",
                User.Role.EMPLOYEE);
        Long id = createAccessRequest(employeeId, "AWS", "not yours");

        assertThatThrownBy(() -> accessRequestService.getRequestHistory(id, otherEmployeeId))
                .isInstanceOf(InsufficientRoleException.class);
    }

    @Test
    @DisplayName("An unknown request has no history")
    void unknownRequestHasNoHistory() {
        assertThatThrownBy(() -> accessRequestService.getRequestHistory(9_999_999L, idOf(MANAGER_EMAIL)))
                .isInstanceOf(AccessRequestNotFoundException.class);
    }

    @Test
    @DisplayName("A default page holds twenty requests and reports its own bounds")
    void pagingDefaults() {
        Long employeeId = idOf(EMPLOYEE_EMAIL);
        for (int i = 0; i < 25; i++) {
            createAccessRequest(employeeId, "App" + i, "filler " + i);
        }

        PagedResponse<AccessRequestResponse> page =
                accessRequestService.getRequestsPage(idOf(MANAGER_EMAIL), null, 0,
                        AccessRequestService.DEFAULT_PAGE_SIZE);

        assertThat(page.items()).hasSize(20);
        assertThat(page.page()).isZero();
        assertThat(page.size()).isEqualTo(20);
        assertThat(page.totalElements()).isEqualTo(25);
        assertThat(page.totalPages()).isEqualTo(2);
        assertThat(page.first()).isTrue();
        assertThat(page.last()).isFalse();
    }

    @Test
    @DisplayName("The default page size is twenty and the maximum is a hundred")
    void pageSizeConstants() {
        assertThat(AccessRequestService.DEFAULT_PAGE_SIZE).isEqualTo(20);
        assertThat(AccessRequestService.MAX_PAGE_SIZE).isEqualTo(100);
    }

    @Test
    @DisplayName("Consecutive pages partition the queue without a repeat or a gap")
    void pagesPartitionTheQueue() {
        Long employeeId = idOf(EMPLOYEE_EMAIL);
        for (int i = 0; i < 25; i++) {
            createAccessRequest(employeeId, "App" + i, "filler " + i);
        }

        Long managerId = idOf(MANAGER_EMAIL);
        List<Long> seen = new ArrayList<>();

        for (int page = 0; page < 3; page++) {
            PagedResponse<AccessRequestResponse> result =
                    accessRequestService.getRequestsPage(managerId, null, page, 10);
            assertThat(result.totalElements()).isEqualTo(25);
            result.items().forEach(item -> seen.add(item.id()));
        }

        // 10 + 10 + 5, each id exactly once.
        assertThat(seen).hasSize(25).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("The paged queue is newest first, like every other list")
    void pagedQueueIsNewestFirst() {
        Long employeeId = idOf(EMPLOYEE_EMAIL);
        createAccessRequest(employeeId, "First", "oldest");
        createAccessRequest(employeeId, "Second", "middle");
        createAccessRequest(employeeId, "Third", "newest");

        assertThat(accessRequestService.getRequestsPage(idOf(MANAGER_EMAIL), null, 0, 10).items())
                .extracting(AccessRequestResponse::application)
                .containsExactly("Third", "Second", "First");
    }

    @Test
    @DisplayName("The order is total even when the timestamps collide")
    void orderIsTotal() {
        // createdAt is written by the database and is not unique. Without the id
        // tie-break, rows sharing a value could come back in either order, and a
        // paged read would then repeat or skip them. Reading the same page twice
        // must give the same answer.
        Long employeeId = idOf(EMPLOYEE_EMAIL);
        for (int i = 0; i < 30; i++) {
            createAccessRequest(employeeId, "App" + i, "filler " + i);
        }

        Long managerId = idOf(MANAGER_EMAIL);
        PagedResponse<AccessRequestResponse> first =
                accessRequestService.getRequestsPage(managerId, null, 0, 10);
        PagedResponse<AccessRequestResponse> again =
                accessRequestService.getRequestsPage(managerId, null, 0, 10);

        assertThat(first.items()).isEqualTo(again.items());
    }

    @Test
    @DisplayName("A status filter narrows both the page and its count")
    void statusFilterNarrowsThePageAndTheCount() {
        Long employeeId = idOf(EMPLOYEE_EMAIL);
        Long managerId = idOf(MANAGER_EMAIL);

        Long approved = createAccessRequest(employeeId, "AWS", "granted");
        approveEveryStage(approved);
        createAccessRequest(employeeId, "Jira", "still waiting");
        Long withdrawn = createAccessRequest(employeeId, "VPN", "changed my mind");
        accessRequestService.withdrawRequest(withdrawn, employeeId, null);

        Long stillPending = accessRequestService.getRequestsByApplicant(employeeId).stream()
                .filter(request -> request.status() == AccessRequest.Status.PENDING)
                .findFirst().orElseThrow().id();

        PagedResponse<AccessRequestResponse> pending =
                accessRequestService.getRequestsPage(managerId, AccessRequest.Status.PENDING, 0, 20);

        assertThat(pending.totalElements()).isEqualTo(1);
        assertThat(pending.items()).extracting(AccessRequestResponse::id).containsExactly(stillPending);

        // The approved and withdrawn rows are counted out, not merely absent from
        // this page: a count that ignored the filter would report all four.
        assertThat(pending.totalElements()).isLessThan(3);
    }

    @Test
    @DisplayName("A page past the end is empty rather than an error")
    void pagePastTheEndIsEmpty() {
        Long employeeId = idOf(EMPLOYEE_EMAIL);
        createAccessRequest(employeeId, "OnlyOne", "the only one");

        PagedResponse<AccessRequestResponse> beyond =
                accessRequestService.getRequestsPage(idOf(MANAGER_EMAIL), null, 99, 20);

        assertThat(beyond.items()).isEmpty();
        assertThat(beyond.totalElements()).isEqualTo(1);
        assertThat(beyond.page()).isEqualTo(99);
        assertThat(beyond.first()).isFalse();
        // Still last, so a reviewer on a stale page has a way back.
        assertThat(beyond.last()).isTrue();
    }

    @Test
    @DisplayName("An empty queue is a well-formed envelope, not a null or a missing page")
    void emptyQueueIsWellFormed() {
        PagedResponse<AccessRequestResponse> empty =
                accessRequestService.getRequestsPage(idOf(MANAGER_EMAIL), null, 0, 20);

        assertThat(empty.items()).isEmpty();
        assertThat(empty.totalElements()).isZero();
        assertThat(empty.totalPages()).isZero();
        assertThat(empty.first()).isTrue();
        assertThat(empty.last()).isTrue();
    }

    @Test
    @DisplayName("An employee cannot page the queue, and a request from one of them cannot be reviewed")
    void employeeCannotPage() {
        Long employeeId = idOf(EMPLOYEE_EMAIL);
        createAccessRequest(employeeId, "AWS", "not for you");

        assertThatThrownBy(() -> accessRequestService.getRequestsPage(employeeId, null, 0, 20))
                .isInstanceOf(InsufficientRoleException.class);
    }

    @Test
    @DisplayName("Paging does not need the caller to be a reviewer when the chain has already said so")
    void pagingRequiresTheCaller() {
        // The service resolves the caller, so an id with no user behind it is
        // reported as missing rather than silently treated as a reviewer.
        Long employeeId = idOf(EMPLOYEE_EMAIL);
        createAccessRequest(employeeId, "AWS", "queue me");

        assertThatThrownBy(() -> accessRequestService.getRequestsPage(9_999_999L, null, 0, 20))
                .isInstanceOf(UserNotFoundException.class);
    }

    @Test
    @DisplayName("The paged items carry the same fields as an unwrapped response")
    void pagedItemsAreFullResponses() {
        Long employeeId = idOf(EMPLOYEE_EMAIL);
        Long id = createAccessRequest(employeeId, "AWS", "shape check");
        accessRequestService.withdrawRequest(id, employeeId, "changed my mind");

        AccessRequestResponse item = accessRequestService
                .getRequestsPage(idOf(MANAGER_EMAIL), null, 0, 20).items().get(0);

        assertThat(item.id()).isEqualTo(id);
        assertThat(item.status()).isEqualTo(AccessRequest.Status.WITHDRAWN);
        assertThat(item.withdrawnAt()).isNotNull();
        assertThat(item.withdrawalReason()).isEqualTo("changed my mind");
        assertThat(item.applicant().id()).isEqualTo(employeeId);
        assertThat(item.createdAt()).isNotNull();
    }

    @Test
    @DisplayName("The returned item list is unmodifiable, so a caller cannot corrupt the envelope")
    void itemListIsUnmodifiable() {
        Long employeeId = idOf(EMPLOYEE_EMAIL);
        createAccessRequest(employeeId, "AWS", "queue me");

        PagedResponse<AccessRequestResponse> page =
                accessRequestService.getRequestsPage(idOf(MANAGER_EMAIL), null, 0, 20);

        assertThatThrownBy(() -> page.items().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("A paged response never carries the applicant's password")
    void pagedResponseNeverCarriesAPassword() {
        Long employeeId = idOf(EMPLOYEE_EMAIL);
        createAccessRequest(employeeId, "AWS", "no secrets here");

        PagedResponse<AccessRequestResponse> page =
                accessRequestService.getRequestsPage(idOf(MANAGER_EMAIL), null, 0, 20);

        assertThat(String.valueOf(page)).doesNotContain(PASSWORD).doesNotContain("$2");
    }
}
