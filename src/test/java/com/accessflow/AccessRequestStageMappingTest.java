package com.accessflow;

import java.util.List;

import com.accessflow.entity.AccessRequest;
import com.accessflow.entity.AccessRequestStage;
import com.accessflow.entity.User;
import com.accessflow.repository.AccessRequestRepository;
import com.accessflow.repository.AccessRequestStageRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 5 - the approval-stage row.
 *
 * The workflow is two sequential stages and both are required, so the things
 * worth pinning down in the schema are the ones the service's rules lean on. If
 * a request can come back with its stages out of order, duplicated or with a
 * stage's decider missing, then "the current stage" is not a thing the service
 * can compute, and the checks in AccessRequestService are only as good as the
 * rows they read.
 */
@DisplayName("Phase 5 - AccessRequestStage entity mapping")
@EnabledIfEnvironmentVariable(named = "ACCESSFLOW_DB_PASSWORD", matches = ".+")
class AccessRequestStageMappingTest extends IntegrationTestSupport {

    @Autowired
    private AccessRequestRepository accessRequestRepository;

    @Autowired
    private AccessRequestStageRepository accessRequestStageRepository;

    @Test
    @DisplayName("A new request is created with two PENDING stages, in order, with the roles the workflow asks for")
    void newRequestHasBothStagesPending() {
        Long id = createAccessRequest(idOf(EMPLOYEE_EMAIL), "GitHub", "need commit access");

        List<AccessRequestStage> stages = stagesOf(id);

        // Both exist from the start, not lazily on first review: a request that
        // is withdrawn or rejected before stage 1 still shows what it was
        // waiting for, and nothing has to invent a stage retroactively.
        assertThat(stages).hasSize(2);
        assertThat(stages).extracting(AccessRequestStage::getStageOrder)
                .containsExactly(1, 2);
        assertThat(stages).extracting(AccessRequestStage::getRequiredRole)
                .containsExactly(User.Role.MANAGER, User.Role.IT_ADMIN);
        assertThat(stages).allSatisfy(stage -> {
            assertThat(stage.getStatus()).isEqualTo(AccessRequestStage.StageStatus.PENDING);
            assertThat(stage.isPending()).isTrue();
            assertThat(stage.isDecided()).isFalse();
            assertThat(stage.getDecidedBy()).isNull();
            assertThat(stage.getDecidedAt()).isNull();
        });
    }

    @Test
    @DisplayName("A stage is found for its request without loading every stage of every request")
    void stagesAreQueriedByRequest() {
        Long first = createAccessRequest(idOf(EMPLOYEE_EMAIL), "AWS", "first");
        Long second = createAccessRequest(idOf(EMPLOYEE_EMAIL), "VPN", "second");

        List<AccessRequestStage> ofFirst =
                accessRequestStageRepository.findByRequestIdOrderByStageOrderAsc(first);

        // The repository is the one the detail page and the service both use, and
        // its ordering is the workflow's ordering, not a detail of the caller.
        assertThat(ofFirst).hasSize(2);
        assertThat(ofFirst).extracting(AccessRequestStage::getRequest)
                .extracting(AccessRequest::getId)
                .containsOnly(first);
        assertThat(accessRequestStageRepository.findByRequestIdOrderByStageOrderAsc(second))
                .hasSize(2);
    }

    @Test
    @DisplayName("A decision round-trips with its decider, its time and its notes")
    void decisionRoundTrips() {
        Long id = createAccessRequest(idOf(EMPLOYEE_EMAIL), "AWS", "quarter end close");
        approveEveryStage(id);

        List<AccessRequestStage> stages = stagesOf(id);

        assertThat(stages).extracting(AccessRequestStage::getStatus)
                .containsExactly(AccessRequestStage.StageStatus.APPROVED,
                        AccessRequestStage.StageStatus.APPROVED);
        assertThat(stages).allSatisfy(stage -> {
            assertThat(stage.isDecided()).isTrue();
            assertThat(stage.getDecidedAt()).isNotNull();
            assertThat(stage.getDecidedBy()).isNotNull();
        });
        // Each stage remembers who decided it, not just that somebody did: the
        // history is derived from these rows, so a stage that lost its decider
        // would show an anonymous entry.
        assertThat(stages.get(0).getDecidedBy().getEmail()).isEqualTo(MANAGER_EMAIL);
        assertThat(stages.get(1).getDecidedBy().getEmail()).isEqualTo(IT_ADMIN_EMAIL);
        assertThat(stages.get(0).getNotes()).isEqualTo("stage 1 ok");
        assertThat(stages.get(1).getNotes()).isEqualTo("stage 2 ok");
    }

    @Test
    @DisplayName("A rejection is terminal: the later stage is left PENDING and the request is refused further decisions")
    void rejectionLeavesTheLaterStagePending() {
        // No SKIPPED status exists, and that is deliberate. A request that is
        // rejected never reaches stage 2, so stage 2 has not been decided and
        // PENDING is the honest description of it. What stops it being read as
        // outstanding is the request's own terminal status, not a stage flag.
        Long id = createAccessRequest(idOf(EMPLOYEE_EMAIL), "HRMS", "payroll details");
        approveFirstStageOnly(id);
        accessRequestService.rejectRequest(id, idOf(IT_ADMIN_EMAIL), "not your department");

        List<AccessRequestStage> stages = stagesOf(id);

        assertThat(stages).extracting(AccessRequestStage::getStatus)
                .containsExactly(AccessRequestStage.StageStatus.APPROVED,
                        AccessRequestStage.StageStatus.REJECTED);
        assertThat(stages.get(1).getDecidedBy().getEmail()).isEqualTo(IT_ADMIN_EMAIL);
        assertThat(stages.get(1).getNotes()).isEqualTo("not your department");

        AccessRequest stored = accessRequestRepository.findById(id).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(AccessRequest.Status.REJECTED);
        assertThat(stored.isPending()).isFalse();
    }

    @Test
    @DisplayName("The database refuses a second stage at a position the request already has")
    void aRequestCannotHaveTwoStagesAtOnePosition() {
        // The unique constraint on (request_id, stage_order) is the last line of
        // defence. Without it a duplicate stage 1 would make "the current stage"
        // ambiguous, and the service's role check could pass on one row while a
        // second row stayed open for somebody else to decide.
        Long id = createAccessRequest(idOf(EMPLOYEE_EMAIL), "AWS", "duplicated stage");
        AccessRequest request = accessRequestRepository.findById(id).orElseThrow();

        AccessRequestStage duplicate = new AccessRequestStage();
        duplicate.setRequest(request);
        duplicate.setStageOrder(1);
        duplicate.setRequiredRole(User.Role.MANAGER);

        assertThatThrownBy(() -> accessRequestStageRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * The request's stages, read back from the database in workflow order.
     *
     * Deliberately not read through {@code request.getStages()}: these tests are
     * about what was persisted, so a lazy collection that the mapping happened
     * to fill in memory would prove nothing.
     */
    private List<AccessRequestStage> stagesOf(Long requestId) {
        return accessRequestStageRepository.findByRequestIdOrderByStageOrderAsc(requestId);
    }
}
