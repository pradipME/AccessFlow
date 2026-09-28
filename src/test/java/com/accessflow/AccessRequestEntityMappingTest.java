package com.accessflow;

import java.util.HashSet;
import java.util.Set;

import com.accessflow.entity.AccessRequest;
import com.accessflow.entity.User;
import com.accessflow.repository.AccessRequestRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Phase 2 - AccessRequest entity mapping")
@EnabledIfEnvironmentVariable(named = "ACCESSFLOW_DB_PASSWORD", matches = ".+")
class AccessRequestEntityMappingTest extends IntegrationTestSupport {

    @Autowired
    private AccessRequestRepository accessRequestRepository;

    @Test
    @DisplayName("A new request defaults to PENDING with no reviewer and no review timestamp")
    void defaultsArePendingAndUnreviewed() {
        Long applicantId = idOf(EMPLOYEE_EMAIL);
        Long id = createAccessRequest(applicantId, "GitHub", "Need commit access");

        AccessRequest stored = accessRequestRepository.findById(id).orElseThrow();

        assertThat(stored.getStatus()).isEqualTo(AccessRequest.Status.PENDING);
        assertThat(stored.isPending()).isTrue();
        assertThat(stored.getReviewedBy()).isNull();
        assertThat(stored.getReviewedAt()).isNull();
        assertThat(stored.getReviewNotes()).isNull();
        assertThat(stored.getCreatedAt()).isNotNull();
        assertThat(stored.getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("Applicant, application and justification round-trip through the database")
    void fieldsRoundTrip() {
        Long applicantId = idOf(EMPLOYEE_EMAIL);
        Long id = createAccessRequest(applicantId, "AWS", "Debug a staging incident");

        AccessRequest stored = accessRequestRepository.findById(id).orElseThrow();

        assertThat(stored.getApplicant().getId()).isEqualTo(applicantId);
        assertThat(stored.getApplicant().getEmail()).isEqualTo(EMPLOYEE_EMAIL);
        assertThat(stored.getApplication()).isEqualTo("AWS");
        assertThat(stored.getJustification()).isEqualTo("Debug a staging incident");
    }

    @Test
    @DisplayName("Status survives a change and is stored as the enum name")
    void statusPersistsAsEnumName() {
        Long applicantId = idOf(EMPLOYEE_EMAIL);
        Long id = createAccessRequest(applicantId, "VPN", "Traveling next week");

        AccessRequest stored = accessRequestRepository.findById(id).orElseThrow();
        stored.setStatus(AccessRequest.Status.REJECTED);
        accessRequestRepository.saveAndFlush(stored);

        AccessRequest reloaded = accessRequestRepository.findById(id).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(AccessRequest.Status.REJECTED);
        assertThat(reloaded.isPending()).isFalse();
    }

    @Test
    @DisplayName("Equality is by id and survives being placed in a HashSet")
    void equalityIsIdBased() {
        Long applicantId = idOf(EMPLOYEE_EMAIL);
        Long id = createAccessRequest(applicantId, "Jira", "Track sprint work");

        AccessRequest first = accessRequestRepository.findById(id).orElseThrow();
        AccessRequest second = accessRequestRepository.findById(id).orElseThrow();

        assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);

        Set<AccessRequest> set = new HashSet<>();
        set.add(first);
        assertThat(set).contains(second);

        // Identity comes from the id, but reflexivity still holds for a not-yet
        // persisted instance: the `this == other` guard keeps an unsaved entity
        // equal to itself, so it behaves correctly in a HashSet before it is saved.
        AccessRequest unsaved = new AccessRequest();
        assertThat(unsaved.equals(unsaved)).isTrue();
        assertThat(unsaved.equals(first)).isFalse();
        assertThat(unsaved.equals(null)).isFalse();
        assertThat(unsaved.equals("not a request")).isFalse();
    }

    @Test
    @DisplayName("toString never exposes the justification or the review notes")
    void toStringOmitsFreeText() {
        Long applicantId = idOf(EMPLOYEE_EMAIL);
        Long id = createAccessRequest(applicantId, "HRMS", "secret-token-abc123");

        AccessRequest stored = accessRequestRepository.findById(id).orElseThrow();
        stored.setReviewNotes("another-sensitive-note");
        accessRequestRepository.saveAndFlush(stored);

        String text = accessRequestRepository.findById(id).orElseThrow().toString();

        assertThat(text).contains("application='HRMS'")
                .contains("status=PENDING")
                .contains("applicantId=" + applicantId);
        assertThat(text).doesNotContain("secret-token-abc123")
                .doesNotContain("another-sensitive-note");
    }

    @Test
    @DisplayName("toString on an unsaved request with no associations does not blow up")
    void toStringHandlesNullAssociations() {
        assertThat(new AccessRequest().toString()).contains("applicantId=null");
    }

    @Test
    @DisplayName("An applicant with a long justification is stored intact")
    void longJustificationIsStored() {
        Long applicantId = idOf(EMPLOYEE_EMAIL);
        String justification = "x".repeat(1000);
        Long id = createAccessRequest(applicantId, "AWS", justification);

        assertThat(accessRequestRepository.findById(id).orElseThrow().getJustification())
                .isEqualTo(justification);
    }

    @Test
    @DisplayName("A request has no withdrawal timestamp or reason until it is withdrawn")
    void withdrawalFieldsAreNullUntilUsed() {
        Long applicantId = idOf(EMPLOYEE_EMAIL);
        Long id = createAccessRequest(applicantId, "VPN", "not withdrawn yet");

        AccessRequest stored = accessRequestRepository.findById(id).orElseThrow();

        assertThat(stored.getWithdrawnAt()).isNull();
        assertThat(stored.getWithdrawalReason()).isNull();
    }

    @Test
    @DisplayName("The withdrawal fields round-trip through the database")
    void withdrawalFieldsRoundTrip() {
        Long applicantId = idOf(EMPLOYEE_EMAIL);
        Long id = createAccessRequest(applicantId, "Jira", "changed my mind");
        String reason = "project postponed";

        accessRequestService.withdrawRequest(id, applicantId, reason);

        AccessRequest stored = accessRequestRepository.findById(id).orElseThrow();
        assertThat(stored.getWithdrawnAt()).isNotNull();
        assertThat(stored.getWithdrawalReason()).isEqualTo(reason);
    }

    @Test
    @DisplayName("A withdrawn request is not pending, like an approved or rejected one")
    void withdrawnIsNotPending() {
        Long applicantId = idOf(EMPLOYEE_EMAIL);
        Long id = createAccessRequest(applicantId, "AWS", "take it back");
        accessRequestService.withdrawRequest(id, applicantId, null);

        AccessRequest stored = accessRequestRepository.findById(id).orElseThrow();

        // isPending() is the single test the service applies before any
        // transition, so WITHDRAWN being false here is what makes it terminal in
        // exactly the same way as the other two outcomes.
        assertThat(stored.getStatus()).isEqualTo(AccessRequest.Status.WITHDRAWN);
        assertThat(stored.isPending()).isFalse();
    }

    @Test
    @DisplayName("All four statuses exist and are the whole set")
    void statusEnumHasExactlyTheFourOutcomes() {
        // The workflow has four outcomes and no more. A fifth would mean a
        // transition that is not covered by the isPending() test, which is why
        // the count is asserted rather than left open.
        assertThat(AccessRequest.Status.values()).containsExactlyInAnyOrder(
                AccessRequest.Status.PENDING,
                AccessRequest.Status.APPROVED,
                AccessRequest.Status.REJECTED,
                AccessRequest.Status.WITHDRAWN);
    }

    @Test
    @DisplayName("toString never exposes the withdrawal reason")
    void toStringOmitsTheWithdrawalReason() {
        // The same reasoning as the justification and the review notes: it is
        // free text an applicant wrote, and this string reaches logs.
        Long applicantId = idOf(EMPLOYEE_EMAIL);
        Long id = createAccessRequest(applicantId, "HRMS", "secret-token-abc123");
        accessRequestService.withdrawRequest(id, applicantId, "withdrawn-secret-xyz789");

        String text = accessRequestRepository.findById(id).orElseThrow().toString();

        assertThat(text).contains("status=WITHDRAWN").doesNotContain("secret-token-abc123")
                .doesNotContain("withdrawn-secret-xyz789");
    }

    @Test
    @DisplayName("A request keeps its own applicant when several users exist")
    void applicantIsNotConfusedBetweenUsers() {
        Long employeeId = idOf(EMPLOYEE_EMAIL);
        Long managerId = idOf(MANAGER_EMAIL);

        Long first = createAccessRequest(employeeId, "GitHub", "employee asking");
        Long second = createAccessRequest(managerId, "GitHub", "manager asking");

        assertThat(accessRequestRepository.findById(first).orElseThrow().getApplicant().getId())
                .isEqualTo(employeeId);
        assertThat(accessRequestRepository.findById(second).orElseThrow().getApplicant().getId())
                .isEqualTo(managerId);
        assertThat(accessRequestRepository.findById(first).orElseThrow().getApplicant().getRole())
                .isEqualTo(User.Role.EMPLOYEE);
    }
}
