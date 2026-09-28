package com.accessflow;

import java.util.List;

import com.accessflow.dto.AccessRequestCreateRequest;
import com.accessflow.dto.RequestHistoryEntry;
import com.accessflow.entity.AccessRequest;
import com.accessflow.entity.User;
import com.accessflow.exception.InvalidAccessRequestStateException;
import com.accessflow.repository.AccessRequestRepository;
import com.accessflow.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 6 - a request that was filed before the approval stages existed.
 *
 * Phase 5 gave every new request two {@code access_request_stages} rows, but the
 * table did not exist before Phase 5, so a request filed in an earlier phase has
 * none. Nothing migrates those rows automatically, and that is deliberate: a
 * backfill would have to invent a decider, and inventing a historical decision is
 * worse than leaving the gap visible. These tests pin down what such a request
 * actually does, so the behaviour is a documented property rather than a
 * surprise found during a rollout.
 *
 * They are written against a request whose stage rows are deleted rather than
 * inserted by hand, so the state being tested is exactly the one the backfill
 * leaves behind: a real request row, with no children.
 */
@DisplayName("Phase 6 - legacy requests filed before the approval stages existed")
@EnabledIfEnvironmentVariable(named = "ACCESSFLOW_DB_PASSWORD", matches = ".+")
class LegacyRequestWithoutStagesTest extends IntegrationTestSupport {

    @Autowired
    private AccessRequestRepository accessRequestRepository;

    @Autowired
    private UserRepository userRepository;

    @Test
    @DisplayName("A legacy request still reads, lists and shows an empty stage list")
    void legacyRequestStillReadsAndLists() {
        // The stages are an addition to the response, so a request without them
        // must not break the reader. An empty list rather than null: a client
        // iterating the stages of every request it sees would otherwise have to
        // null-check a field that is never null on a new request.
        Long id = fileLegacyRequest();

        assertThat(accessRequestService.getRequestById(id, idOf(EMPLOYEE_EMAIL)).stages())
                .isEmpty();
        assertThat(accessRequestService.getRequestById(id, idOf(MANAGER_EMAIL)).stages())
                .isEmpty();
        assertThat(accessRequestService.getRequestsByApplicant(idOf(EMPLOYEE_EMAIL)))
                .anySatisfy(response -> {
                    assertThat(response.id()).isEqualTo(id);
                    assertThat(response.stages()).isEmpty();
                });
        assertThat(accessRequestService.getAllRequests())
                .anySatisfy(response -> {
                    assertThat(response.id()).isEqualTo(id);
                    assertThat(response.stages()).isEmpty();
                });
    }

    @Test
    @DisplayName("A legacy PENDING request has no outstanding stage, so it is not presented as awaiting one")
    void legacyRequestHasNoNextStage() {
        Long id = fileLegacyRequest();

        // nextStage() is null rather than a fabricated stage 1. This is what keeps
        // the review queue from telling a MANAGER that a request is waiting on
        // their stage when there is no stage row to decide.
        assertThat(accessRequestService.getRequestById(id, idOf(MANAGER_EMAIL)).nextStage())
                .isNull();
    }

    @Test
    @DisplayName("A legacy PENDING request cannot be approved or rejected, and says why with a 409")
    void legacyRequestCannotBeDecided() {
        // The honest outcome, and the reason the backfill has to be reviewed
        // rather than run blind: a request with no stage rows has no outstanding
        // stage, so there is nothing to decide. It is reported as a state the
        // workflow is not in, not as a permissions problem - the reviewer here is
        // a SUPER_ADMIN, who may take any stage that exists.
        Long id = fileLegacyRequest();

        assertThatThrownBy(() -> accessRequestService
                .approveRequest(id, idOf(SUPER_ADMIN_EMAIL), "granted"))
                .isInstanceOf(InvalidAccessRequestStateException.class);
        assertThatThrownBy(() -> accessRequestService
                .rejectRequest(id, idOf(SUPER_ADMIN_EMAIL), "refused"))
                .isInstanceOf(InvalidAccessRequestStateException.class);

        assertThat(accessRequestRepository.findById(id).orElseThrow().getStatus())
                .isEqualTo(AccessRequest.Status.PENDING);
    }

    @Test
    @DisplayName("A legacy PENDING request can still be withdrawn by its applicant")
    void legacyRequestCanStillBeWithdrawn() {
        // Withdrawal does not go through a stage, so it is the one transition
        // that still works. That is worth pinning down because it is the only
        // escape a legacy request has: the applicant can close it even though no
        // reviewer can resolve it.
        Long id = fileLegacyRequest();

        accessRequestService.withdrawRequest(id, idOf(EMPLOYEE_EMAIL), "no longer needed");

        assertThat(accessRequestRepository.findById(id).orElseThrow().getStatus())
                .isEqualTo(AccessRequest.Status.WITHDRAWN);
    }

    @Test
    @DisplayName("A legacy request's history shows the submission and no stage decision")
    void legacyHistoryHasNoStageDecision() {
        Long id = fileLegacyRequest();

        List<RequestHistoryEntry> history =
                accessRequestService.getRequestHistory(id, idOf(EMPLOYEE_EMAIL));

        // One entry, not two. The history is derived from the stage rows, and
        // there are none, so it reports only what is actually recorded rather
        // than inventing a reviewer to fill the gap.
        assertThat(history).extracting(RequestHistoryEntry::action)
                .containsExactly(RequestHistoryEntry.Action.SUBMITTED);
    }

    @Test
    @DisplayName("A legacy request resolved before Phase 5 keeps its decision on the request row")
    void legacyResolvedRequestStillReadsItsDecision() {
        // The other half of the backfill question. A request decided before the
        // stages existed has reviewedBy/reviewedAt on the request row and no
        // stage rows, and the read path still shows that decision: the response
        // does not depend on the stages being there to render a resolved status.
        Long id = createAccessRequest(idOf(EMPLOYEE_EMAIL), "AWS", "decided long ago");
        User manager = userRepository.findByEmail(MANAGER_EMAIL).orElseThrow();

        AccessRequest request = accessRequestRepository.findById(id).orElseThrow();
        request.setStatus(AccessRequest.Status.APPROVED);
        request.setReviewedBy(manager);
        request.setReviewedAt(java.time.LocalDateTime.now());
        request.setReviewNotes("granted before the stages existed");
        request.getStages().clear();
        accessRequestRepository.saveAndFlush(request);

        assertThat(accessRequestRepository.findAll()
                .stream()
                .filter(candidate -> candidate.getId().equals(id))
                .findFirst()
                .orElseThrow()
                .getStages())
                .isEmpty();

        var response = accessRequestService.getRequestById(id, idOf(EMPLOYEE_EMAIL));
        assertThat(response.status()).isEqualTo(AccessRequest.Status.APPROVED);
        assertThat(response.reviewedBy().email()).isEqualTo(MANAGER_EMAIL);
        assertThat(response.stages()).isEmpty();
        assertThat(response.nextStage()).isNull();
    }

    /**
     * Files a request the way a pre-Phase-5 deployment would have, then removes
     * its stage rows.
     *
     * Going through the service first means the row is a real one, with the same
     * columns and the same applicant a deployment would have written. The delete
     * is what leaves the shape a backfill has to deal with: a request with no
     * children, and no way to tell from the request row alone that it is
     * incomplete.
     */
    private Long fileLegacyRequest() {
        Long id = accessRequestService
                .createRequest(new AccessRequestCreateRequest("AWS", "filed before the stages existed"),
                        idOf(EMPLOYEE_EMAIL))
                .id();

        AccessRequest request = accessRequestRepository.findById(id).orElseThrow();
        request.getStages().clear();
        accessRequestRepository.saveAndFlush(request);

        return id;
    }
}
