package com.accessflow;

import com.accessflow.entity.AccessRequest;
import com.accessflow.repository.AccessRequestRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Phase 2 - AccessRequestRepository")
@EnabledIfEnvironmentVariable(named = "ACCESSFLOW_DB_PASSWORD", matches = ".+")
class AccessRequestRepositoryTest extends IntegrationTestSupport {

    @Autowired
    private AccessRequestRepository accessRequestRepository;

    @Test
    @DisplayName("findByApplicantId is scoped to one applicant and ordered newest first")
    void findByApplicantIsScopedAndOrdered() {
        Long employeeId = idOf(EMPLOYEE_EMAIL);
        Long otherId = idOf(MANAGER_EMAIL);

        Long first = createAccessRequest(employeeId, "GitHub", "first");
        Long second = createAccessRequest(employeeId, "Jira", "second");
        createAccessRequest(otherId, "AWS", "not mine");

        List<AccessRequest> found =
                accessRequestRepository.findByApplicantIdOrderByCreatedAtDescIdDesc(employeeId);

        assertThat(found).extracting(AccessRequest::getId)
                .containsExactlyInAnyOrder(first, second);
        assertThat(found).allSatisfy(request ->
                assertThat(request.getApplicant().getId()).isEqualTo(employeeId));
        assertThat(found).extracting(AccessRequest::getCreatedAt)
                .isSortedAccordingTo((a, b) -> b.compareTo(a));
    }

    @Test
    @DisplayName("findByApplicantId returns an empty list for an applicant with no requests")
    void findByApplicantWithNoRequestsIsEmpty() {
        assertThat(accessRequestRepository.findByApplicantIdOrderByCreatedAtDescIdDesc(idOf(EMPLOYEE_EMAIL)))
                .isEmpty();
    }

    @Test
    @DisplayName("findByStatus returns only requests in that status")
    void findByStatusIsScoped() {
        Long employeeId = idOf(EMPLOYEE_EMAIL);

        Long pending = createAccessRequest(employeeId, "GitHub", "still waiting");
        Long approved = createAccessRequest(employeeId, "Jira", "to be approved");
        approveEveryStage(approved);

        List<AccessRequest> pendingOnly =
                accessRequestRepository.findByStatusOrderByCreatedAtDescIdDesc(AccessRequest.Status.PENDING);

        assertThat(pendingOnly).extracting(AccessRequest::getId).contains(pending).doesNotContain(approved);
    }

    @Test
    @DisplayName("findByStatus returns approved requests once they are approved")
    void approvedRequestsMoveToTheApprovedStatus() {
        Long employeeId = idOf(EMPLOYEE_EMAIL);
        Long managerId = idOf(MANAGER_EMAIL);
        Long id = createAccessRequest(employeeId, "VPN", "needs approving");
        approveEveryStage(id);

        List<AccessRequest> approved =
                accessRequestRepository.findByStatusOrderByCreatedAtDescIdDesc(AccessRequest.Status.APPROVED);

        assertThat(approved).extracting(AccessRequest::getId).contains(id);
        assertThat(accessRequestRepository.findByStatusOrderByCreatedAtDescIdDesc(AccessRequest.Status.PENDING))
                .extracting(AccessRequest::getId).doesNotContain(id);
    }

    @Test
    @DisplayName("A rejected request carries its reviewer, review time and notes")
    void rejectedRequestKeepsTheDecision() {
        Long employeeId = idOf(EMPLOYEE_EMAIL);
        Long managerId = idOf(MANAGER_EMAIL);

        Long id = createAccessRequest(employeeId, "HRMS", "need payroll access");
        accessRequestService.rejectRequest(id, managerId, "not a business need");

        AccessRequest stored = accessRequestRepository.findById(id).orElseThrow();

        assertThat(stored.getStatus()).isEqualTo(AccessRequest.Status.REJECTED);
        assertThat(stored.getReviewedBy().getId()).isEqualTo(managerId);
        assertThat(stored.getReviewedAt()).isNotNull();
        assertThat(stored.getReviewNotes()).isEqualTo("not a business need");
    }

    @Test
    @DisplayName("findAll returns every request regardless of applicant")
    void findAllReturnsEverything() {
        createAccessRequest(idOf(EMPLOYEE_EMAIL), "GitHub", "one");
        createAccessRequest(idOf(MANAGER_EMAIL), "Jira", "two");

        assertThat(accessRequestRepository.findAll()).hasSize(2);
    }

    @Test
    @DisplayName("findAllByOrderByCreatedAtDescIdDesc is newest first, matching the other list queries")
    void findAllIsOrdered() {
        Long employeeId = idOf(EMPLOYEE_EMAIL);

        createAccessRequest(employeeId, "First", "oldest");
        createAccessRequest(employeeId, "Second", "middle");
        createAccessRequest(employeeId, "Third", "newest");

        assertThat(accessRequestRepository.findAllByOrderByCreatedAtDescIdDesc())
                .extracting(AccessRequest::getApplication)
                .containsExactly("Third", "Second", "First");
    }

    @Test
    @DisplayName("updatedAt moves forward when the status changes")
    void updatedAtAdvancesOnChange() {
        Long employeeId = idOf(EMPLOYEE_EMAIL);

        Long id = createAccessRequest(employeeId, "VPN", "needs approving");
        AccessRequest stored = accessRequestRepository.findById(id).orElseThrow();
        LocalDateTime createdAt = stored.getCreatedAt();

        approveEveryStage(id);
        accessRequestRepository.flush();

        AccessRequest reloaded = accessRequestRepository.findById(id).orElseThrow();
        assertThat(reloaded.getCreatedAt()).isEqualTo(createdAt);
        assertThat(reloaded.getUpdatedAt()).isAfterOrEqualTo(createdAt);
    }
}
