package com.accessflow;

import com.accessflow.dto.AccessRequestCreateRequest;
import com.accessflow.dto.AccessRequestResponse;
import com.accessflow.dto.AccessRequestStageResponse;
import com.accessflow.entity.AccessRequest;
import com.accessflow.entity.AccessRequestStage;
import com.accessflow.entity.User;
import com.accessflow.exception.AccessRequestNotFoundException;
import com.accessflow.exception.InsufficientRoleException;
import com.accessflow.exception.InvalidAccessRequestStateException;
import com.accessflow.exception.SelfApprovalNotAllowedException;
import com.accessflow.exception.UserNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

@DisplayName("Phase 2 - AccessRequestService workflow")
@EnabledIfEnvironmentVariable(named = "ACCESSFLOW_DB_PASSWORD", matches = ".+")
class AccessRequestServiceTest extends IntegrationTestSupport {

    @Test
    @DisplayName("A new request is PENDING, attributed to the applicant, with no reviewer")
    void createFilesAPendingRequest() {
        Long employeeId = idOf(EMPLOYEE_EMAIL);

        AccessRequestResponse created = accessRequestService.createRequest(
                new AccessRequestCreateRequest("GitHub", "Need commit access"), employeeId);

        assertThat(created.id()).isNotNull();
        assertThat(created.status()).isEqualTo(AccessRequest.Status.PENDING);
        assertThat(created.application()).isEqualTo("GitHub");
        assertThat(created.justification()).isEqualTo("Need commit access");
        assertThat(created.applicant().id()).isEqualTo(employeeId);
        assertThat(created.applicant().email()).isEqualTo(EMPLOYEE_EMAIL);
        assertThat(created.reviewedBy()).isNull();
        assertThat(created.reviewedAt()).isNull();
        assertThat(created.createdAt()).isNotNull();
    }

    @Test
    @DisplayName("Creating a request for an unknown applicant fails rather than orphaning the row")
    void createWithUnknownApplicantFails() {
        assertThatThrownBy(() -> accessRequestService.createRequest(
                new AccessRequestCreateRequest("GitHub", "orphan"), 9_999_999L))
                .isInstanceOf(UserNotFoundException.class);
    }

    @Test
    @DisplayName("The first approval advances stage 1 and leaves the request PENDING, unrecorded as decided")
    void firstApprovalLeavesTheRequestPending() {
        // Since Phase 5 an approval decides a stage, not the request. The request
        // is only resolved once every stage is approved, so it stays PENDING here -
        // and reviewedBy/reviewedAt stay null, because no reviewer has resolved
        // it yet. That is what keeps "a reviewer decided this" true of those
        // fields.
        Long employeeId = idOf(EMPLOYEE_EMAIL);
        Long managerId = idOf(MANAGER_EMAIL);
        Long id = createAccessRequest(employeeId, "AWS", "Debug a staging incident");

        AccessRequestResponse afterStageOne = accessRequestService
                .approveRequest(id, managerId, "business need");

        assertThat(afterStageOne.status()).isEqualTo(AccessRequest.Status.PENDING);
        assertThat(afterStageOne.reviewedBy()).isNull();
        assertThat(afterStageOne.reviewedAt()).isNull();
        assertThat(afterStageOne.reviewNotes()).isNull();

        assertThat(afterStageOne.stages())
                .extracting(AccessRequestStageResponse::order, AccessRequestStageResponse::status)
                .containsExactly(
                        tuple(1, AccessRequestStage.StageStatus.APPROVED),
                        tuple(2, AccessRequestStage.StageStatus.PENDING));
    }

    @Test
    @DisplayName("The second approval resolves the request and records the decider of the last stage")
    void secondApprovalRecordsTheDecision() {
        Long employeeId = idOf(EMPLOYEE_EMAIL);
        Long itAdminId = idOf(IT_ADMIN_EMAIL);
        Long id = createAccessRequest(employeeId, "AWS", "Debug a staging incident");
        approveFirstStageOnly(id);

        AccessRequestResponse approved = accessRequestService
                .approveRequest(id, itAdminId, "provisioning approved");

        assertThat(approved.status()).isEqualTo(AccessRequest.Status.APPROVED);
        assertThat(approved.reviewedBy().id()).isEqualTo(itAdminId);
        assertThat(approved.reviewedAt()).isNotNull();
        assertThat(approved.reviewNotes()).isEqualTo("provisioning approved");
    }

    @Test
    @DisplayName("Approving without notes is allowed at every stage and leaves the notes null")
    void approveWithoutNotesIsAllowed() {
        Long employeeId = idOf(EMPLOYEE_EMAIL);
        Long id = createAccessRequest(employeeId, "VPN", "Traveling next week");

        accessRequestService.approveRequest(id, idOf(MANAGER_EMAIL), null);
        AccessRequestResponse approved = accessRequestService
                .approveRequest(id, idOf(IT_ADMIN_EMAIL), null);

        assertThat(approved.status()).isEqualTo(AccessRequest.Status.APPROVED);
        assertThat(approved.reviewNotes()).isNull();
        assertThat(approved.stages())
                .extracting(AccessRequestStageResponse::notes)
                .containsOnlyNulls();
    }

    @Test
    @DisplayName("Rejecting records the reason and moves the request to REJECTED")
    void rejectRecordsTheReason() {
        Long employeeId = idOf(EMPLOYEE_EMAIL);
        Long managerId = idOf(MANAGER_EMAIL);
        Long id = createAccessRequest(employeeId, "HRMS", "Need payroll details");

        AccessRequestResponse rejected = accessRequestService.rejectRequest(
                id, managerId, "no business need");

        assertThat(rejected.status()).isEqualTo(AccessRequest.Status.REJECTED);
        assertThat(rejected.reviewedBy().id()).isEqualTo(managerId);
        assertThat(rejected.reviewNotes()).isEqualTo("no business need");
    }

    @Test
    @DisplayName("An applicant cannot approve their own request, even as a manager")
    void selfApprovalIsRefused() {
        Long managerId = idOf(MANAGER_EMAIL);
        Long id = createAccessRequest(managerId, "AWS", "I would like this");

        assertThatThrownBy(() -> accessRequestService.approveRequest(id, managerId, "self"))
                .isInstanceOf(SelfApprovalNotAllowedException.class);

        assertThatThrownBy(() -> accessRequestService.rejectRequest(id, managerId, "self"))
                .isInstanceOf(SelfApprovalNotAllowedException.class);

        assertThat(accessRequestService.getRequestById(id, managerId).status())
                .isEqualTo(AccessRequest.Status.PENDING);
    }

    @Test
    @DisplayName("A self-approving manager is refused for self-approval, not for holding the wrong role")
    void selfApprovalOutranksTheRoleCheck() {
        // Stage 1 asks for a MANAGER and this caller is one, so the only rule that
        // can refuse them is the self-approval rule. If the order were reversed the
        // caller would be told they hold the wrong role, which would be a
        // misleading reason for the same 403.
        Long managerId = idOf(MANAGER_EMAIL);
        Long id = createAccessRequest(managerId, "AWS", "I would like this");

        assertThatThrownBy(() -> accessRequestService.approveRequest(id, managerId, "self"))
                .isInstanceOf(SelfApprovalNotAllowedException.class)
                .isNotInstanceOf(InsufficientRoleException.class);
    }

    @Test
    @DisplayName("Approving an already resolved request is a conflict, and changes nothing")
    void doubleApprovalIsAConflict() {
        // The chain is complete, so a third decision has no stage left to apply to
        // and the request is terminal. This is the same conflict a second review
        // has always raised, reached through a longer path.
        Long employeeId = idOf(EMPLOYEE_EMAIL);
        Long managerId = idOf(MANAGER_EMAIL);
        Long itAdminId = idOf(IT_ADMIN_EMAIL);
        Long id = createAccessRequest(employeeId, "Jira", "sprint work");
        approveEveryStage(id);

        assertThatThrownBy(() -> accessRequestService.approveRequest(id, managerId, "again"))
                .isInstanceOf(InvalidAccessRequestStateException.class)
                .hasMessageContaining("APPROVED");

        assertThatThrownBy(() -> accessRequestService.rejectRequest(id, itAdminId, "changed my mind"))
                .isInstanceOf(InvalidAccessRequestStateException.class);

        AccessRequestResponse unchanged = accessRequestService.getRequestById(id, managerId);
        assertThat(unchanged.reviewedBy().id()).isEqualTo(itAdminId);
        assertThat(unchanged.reviewNotes()).isEqualTo("stage 2 ok");
    }

    @Test
    @DisplayName("A rejected request can no longer be approved")
    void rejectedRequestCannotBeApproved() {
        Long employeeId = idOf(EMPLOYEE_EMAIL);
        Long managerId = idOf(MANAGER_EMAIL);
        Long otherManagerId = idOf(IT_ADMIN_EMAIL);
        Long id = createAccessRequest(employeeId, "GitHub", "repo access");
        accessRequestService.rejectRequest(id, managerId, "no");

        assertThatThrownBy(() -> accessRequestService.approveRequest(id, otherManagerId, "yes"))
                .isInstanceOf(InvalidAccessRequestStateException.class)
                .hasMessageContaining("REJECTED");
    }

    @Test
    @DisplayName("An approved request can no longer be rejected")
    void approvedRequestCannotBeRejected() {
        Long employeeId = idOf(EMPLOYEE_EMAIL);
        Long id = createAccessRequest(employeeId, "GitHub", "repo access");
        approveEveryStage(id);

        // A SUPER_ADMIN could have rejected this at either stage while it was open.
        assertThatThrownBy(() -> accessRequestService
                .rejectRequest(id, idOf(SUPER_ADMIN_EMAIL), "changed my mind"))
                .isInstanceOf(InvalidAccessRequestStateException.class);
    }

    @Test
    @DisplayName("An employee cannot review a request, and the request is left pending")
    void employeeCannotReview() {
        Long employeeId = idOf(EMPLOYEE_EMAIL);
        Long id = createAccessRequest(employeeId, "AWS", "please");

        assertThatThrownBy(() -> accessRequestService.approveRequest(id, employeeId, "self"))
                .isInstanceOf(SelfApprovalNotAllowedException.class);

        Long otherEmployeeId = createUser("EMP-2ND", "actor.employee2@accessflow.local",
                User.Role.EMPLOYEE);

        assertThatThrownBy(() -> accessRequestService.approveRequest(id, otherEmployeeId, "me too"))
                .isInstanceOf(InsufficientRoleException.class);

        assertThat(accessRequestService.getRequestById(id, employeeId).status())
                .isEqualTo(AccessRequest.Status.PENDING);
    }

    @Test
    @DisplayName("A MANAGER may decide stage 1, an IT_ADMIN stage 2, and a SUPER_ADMIN either")
    void stageRolesAreScopedButSuperAdminIsNot() {
        // The role each stage asks for is enforced, so a MANAGER cannot jump to
        // stage 2 and an IT_ADMIN cannot take stage 1. SUPER_ADMIN may act at any
        // stage: it could review every request before Phase 5 and that has not
        // been taken away.
        Long employeeId = idOf(EMPLOYEE_EMAIL);
        Long managerId = idOf(MANAGER_EMAIL);
        Long itAdminId = idOf(IT_ADMIN_EMAIL);
        Long superAdminId = idOf(SUPER_ADMIN_EMAIL);

        Long byManager = createAccessRequest(employeeId, "App-A", "manager then admin");
        assertThatThrownBy(() -> accessRequestService.approveRequest(byManager, itAdminId, "out of turn"))
                .isInstanceOf(InsufficientRoleException.class);
        accessRequestService.approveRequest(byManager, managerId, "stage 1");
        accessRequestService.approveRequest(byManager, itAdminId, "stage 2");
        assertThat(accessRequestService.getRequestById(byManager, managerId).status())
                .isEqualTo(AccessRequest.Status.APPROVED);

        Long byAdmin = createAccessRequest(employeeId, "App-B", "admin cannot start it");
        assertThatThrownBy(() -> accessRequestService.approveRequest(byAdmin, itAdminId, "not my stage"))
                .isInstanceOf(InsufficientRoleException.class);
        // Still PENDING: the refused attempt decided nothing.
        assertThat(accessRequestService.getRequestById(byAdmin, itAdminId).status())
                .isEqualTo(AccessRequest.Status.PENDING);

        Long bySuperAdmin = createAccessRequest(employeeId, "App-C", "admin overrides both");
        accessRequestService.approveRequest(bySuperAdmin, superAdminId, "stage 1 by admin");
        accessRequestService.approveRequest(bySuperAdmin, superAdminId, "stage 2 by admin");
        assertThat(accessRequestService.getRequestById(bySuperAdmin, superAdminId).status())
                .isEqualTo(AccessRequest.Status.APPROVED);
    }

    @Test
    @DisplayName("A refused attempt to decide a stage leaves the request and the stage untouched")
    void refusedDecisionChangesNothing() {
        Long employeeId = idOf(EMPLOYEE_EMAIL);
        Long managerId = idOf(MANAGER_EMAIL);
        Long itAdminId = idOf(IT_ADMIN_EMAIL);
        Long id = createAccessRequest(employeeId, "AWS", "resist tampering");

        // Stage 1 asks for a MANAGER, so an IT_ADMIN reaching for it is refused
        // before anything is written.
        assertThatThrownBy(() -> accessRequestService.approveRequest(id, itAdminId, "not my stage"))
                .isInstanceOf(InsufficientRoleException.class);

        AccessRequestResponse untouched = accessRequestService.getRequestById(id, managerId);
        assertThat(untouched.status()).isEqualTo(AccessRequest.Status.PENDING);
        assertThat(untouched.stages())
                .extracting(AccessRequestStageResponse::order, AccessRequestStageResponse::status)
                .containsExactly(
                        tuple(1, AccessRequestStage.StageStatus.PENDING),
                        tuple(2, AccessRequestStage.StageStatus.PENDING));

        // The chain is untouched, so the correct reviewers can still run it in order.
        accessRequestService.approveRequest(id, managerId, "stage 1 ok");
        accessRequestService.approveRequest(id, itAdminId, "stage 2 ok");
        assertThat(accessRequestService.getRequestById(id, managerId).status())
                .isEqualTo(AccessRequest.Status.APPROVED);
    }

    @Test
    @DisplayName("Reviewing an unknown request reports it as not found")
    void reviewUnknownRequestIsNotFound() {
        assertThatThrownBy(() -> accessRequestService.approveRequest(9_999_999L, idOf(MANAGER_EMAIL), "x"))
                .isInstanceOf(AccessRequestNotFoundException.class);
    }

    @Test
    @DisplayName("An applicant may read their own request")
    void applicantMayReadOwnRequest() {
        Long employeeId = idOf(EMPLOYEE_EMAIL);
        Long id = createAccessRequest(employeeId, "AWS", "mine");

        assertThat(accessRequestService.getRequestById(id, employeeId).id()).isEqualTo(id);
    }

    @Test
    @DisplayName("An employee may not read somebody else's request")
    void employeeMayNotReadAnotherRequest() {
        Long employeeId = idOf(EMPLOYEE_EMAIL);
        Long otherEmployeeId = createUser("EMP-2ND", "actor.employee2@accessflow.local",
                User.Role.EMPLOYEE);
        Long id = createAccessRequest(employeeId, "AWS", "not yours");

        assertThatThrownBy(() -> accessRequestService.getRequestById(id, otherEmployeeId))
                .isInstanceOf(InsufficientRoleException.class);
    }

    @Test
    @DisplayName("A reviewer may read anybody's request")
    void reviewerMayReadAnyRequest() {
        Long employeeId = idOf(EMPLOYEE_EMAIL);
        Long id = createAccessRequest(employeeId, "AWS", "readable by a manager");

        assertThat(accessRequestService.getRequestById(id, idOf(MANAGER_EMAIL)).id()).isEqualTo(id);
        assertThat(accessRequestService.getRequestById(id, idOf(IT_ADMIN_EMAIL)).id()).isEqualTo(id);
        assertThat(accessRequestService.getRequestById(id, idOf(SUPER_ADMIN_EMAIL)).id()).isEqualTo(id);
    }

    @Test
    @DisplayName("An unknown request id reports not found rather than a null response")
    void getUnknownRequestIsNotFound() {
        assertThatThrownBy(() -> accessRequestService.getRequestById(9_999_999L, idOf(MANAGER_EMAIL)))
                .isInstanceOf(AccessRequestNotFoundException.class)
                .hasMessageContaining("9999999");
    }

    @Test
    @DisplayName("Listing by applicant returns that applicant's requests only")
    void listByApplicant() {
        Long employeeId = idOf(EMPLOYEE_EMAIL);
        Long managerId = idOf(MANAGER_EMAIL);

        Long mine = createAccessRequest(employeeId, "GitHub", "mine");
        createAccessRequest(managerId, "Jira", "not mine");

        assertThat(accessRequestService.getRequestsByApplicant(employeeId))
                .extracting(AccessRequestResponse::id).containsExactly(mine);
    }

    @Test
    @DisplayName("Listing by status reflects the workflow, and getAll spans applicants")
    void listByStatusAndAll() {
        Long employeeId = idOf(EMPLOYEE_EMAIL);

        Long stillPending = createAccessRequest(employeeId, "GitHub", "pending");
        Long approved = createAccessRequest(employeeId, "Jira", "to approve");
        approveEveryStage(approved);

        assertThat(accessRequestService.getRequestsByStatus(AccessRequest.Status.PENDING))
                .extracting(AccessRequestResponse::id).contains(stillPending).doesNotContain(approved);

        assertThat(accessRequestService.getRequestsByStatus(AccessRequest.Status.APPROVED))
                .extracting(AccessRequestResponse::id).contains(approved).doesNotContain(stillPending);

        assertThat(accessRequestService.getAllRequests())
                .extracting(AccessRequestResponse::id)
                .contains(stillPending, approved);
    }

    @Test
    @DisplayName("Every list is newest first, so the three views agree on ordering")
    void allListsAreNewestFirst() {
        Long employeeId = idOf(EMPLOYEE_EMAIL);

        createAccessRequest(employeeId, "First", "oldest");
        createAccessRequest(employeeId, "Second", "middle");
        createAccessRequest(employeeId, "Third", "newest");

        assertThat(accessRequestService.getAllRequests())
                .extracting(AccessRequestResponse::application)
                .containsExactly("Third", "Second", "First");

        assertThat(accessRequestService.getRequestsByApplicant(employeeId))
                .extracting(AccessRequestResponse::application)
                .containsExactly("Third", "Second", "First");

        assertThat(accessRequestService.getRequestsByStatus(AccessRequest.Status.PENDING))
                .extracting(AccessRequestResponse::application)
                .containsExactly("Third", "Second", "First");
    }

    @Test
    @DisplayName("The response never carries the applicant's password")
    void responseNeverCarriesAPassword() {
        Long employeeId = idOf(EMPLOYEE_EMAIL);
        Long id = createAccessRequest(employeeId, "AWS", "no secrets here");

        AccessRequestResponse response = accessRequestService.getRequestById(id, employeeId);

        String rendered = String.valueOf(response);
        assertThat(rendered).doesNotContain(PASSWORD).doesNotContain("$2");
    }
}
