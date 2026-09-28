package com.accessflow;

import com.accessflow.entity.AccessRequest;
import com.accessflow.repository.AccessRequestRepository;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * Decisions made through the review forms.
 *
 * These assert the same rules the Phase 2 API tests assert, because the pages
 * call the same service: whatever the browser does, the request can only move
 * PENDING -> APPROVED or PENDING -> REJECTED, once, and never by its applicant.
 */
@DisplayName("Phase 3 - approving and rejecting in the browser")
@EnabledIfEnvironmentVariable(named = "ACCESSFLOW_DB_PASSWORD", matches = ".+")
class WebAccessRequestReviewTest extends IntegrationTestSupport {

    private static final String OTHER_EMPLOYEE_EMAIL = "review.other@accessflow.local";

    @Autowired
    private AccessRequestRepository accessRequestRepository;

    @Test
    @DisplayName("The two approval forms resolve the request, and only the last one records the decider")
    void approveRecordsTheDecision() throws Exception {
        // Both stages have to be decided. A MANAGER decides stage 1 and an
        // IT_ADMIN stage 2, and only the second form leaves the request resolved,
        // with the decider of the last stage on the row.
        Long id = createAccessRequest(idOf(EMPLOYEE_EMAIL), "GitHub", "need commit access");

        mockMvc.perform(post("/requests/" + id + "/approve")
                        .with(signedInAs(MANAGER_EMAIL))
                        .with(csrf())
                        .param("notes", "business need"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/requests/" + id));

        AccessRequest afterFirstStage = accessRequestRepository.findById(id).orElseThrow();
        assertThat(afterFirstStage.getStatus()).isEqualTo(AccessRequest.Status.PENDING);
        assertThat(afterFirstStage.getReviewedBy()).isNull();

        mockMvc.perform(post("/requests/" + id + "/approve")
                        .with(signedInAs(IT_ADMIN_EMAIL))
                        .with(csrf())
                        .param("notes", "provisioning approved"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/requests/" + id));

        AccessRequest stored = accessRequestRepository.findById(id).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(AccessRequest.Status.APPROVED);
        assertThat(stored.getReviewedBy().getId()).isEqualTo(idOf(IT_ADMIN_EMAIL));
        assertThat(stored.getReviewedAt()).isNotNull();
        assertThat(stored.getReviewNotes()).isEqualTo("provisioning approved");
    }

    @Test
    @DisplayName("A reviewer whose stage has already passed is refused a 403 page")
    void approvingOutOfTurnIsRefused() throws Exception {
        // A MANAGER has already approved stage 1, so the outstanding stage is
        // IT_ADMIN's. The same manager posting the form again is refused by the
        // service, and the page shows the error rather than pretending it worked.
        Long id = createAccessRequest(idOf(EMPLOYEE_EMAIL), "AWS", "out of turn");
        approveFirstStageOnly(id);

        mockMvc.perform(post("/requests/" + id + "/approve")
                        .with(signedInAs(MANAGER_EMAIL))
                        .with(csrf())
                        .param("notes", "again"))
                .andExpect(status().isForbidden())
                .andExpect(view().name("error"));

        AccessRequest stored = accessRequestRepository.findById(id).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(AccessRequest.Status.PENDING);
    }

    @Test
    @DisplayName("Approving without notes is allowed at every stage")
    void approveWithoutNotes() throws Exception {
        Long id = createAccessRequest(idOf(EMPLOYEE_EMAIL), "VPN", "travelling next week");

        mockMvc.perform(post("/requests/" + id + "/approve")
                        .with(signedInAs(MANAGER_EMAIL))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());

        mockMvc.perform(post("/requests/" + id + "/approve")
                        .with(signedInAs(IT_ADMIN_EMAIL))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());

        AccessRequest stored = accessRequestRepository.findById(id).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(AccessRequest.Status.APPROVED);
        assertThat(stored.getReviewNotes()).isNull();
    }

    @Test
    @DisplayName("Rejecting at either stage terminates the request and records the reason")
    void rejectRecordsTheReason() throws Exception {
        // A rejection needs no chain: it ends the request wherever it lands. This
        // one is refused at stage 2, after the MANAGER has already approved, and
        // the history then holds an approval and a rejection.
        Long id = createAccessRequest(idOf(EMPLOYEE_EMAIL), "HRMS", "payroll details");
        approveFirstStageOnly(id);

        mockMvc.perform(post("/requests/" + id + "/reject")
                        .with(signedInAs(IT_ADMIN_EMAIL))
                        .with(csrf())
                        .param("notes", "no business need"))
                .andExpect(status().is3xxRedirection());

        AccessRequest stored = accessRequestRepository.findById(id).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(AccessRequest.Status.REJECTED);
        assertThat(stored.getReviewedBy().getId()).isEqualTo(idOf(IT_ADMIN_EMAIL));
        assertThat(stored.getReviewNotes()).isEqualTo("no business need");
    }


    @Test
    @DisplayName("A rejection without a reason is refused and the request stays pending")
    void rejectionRequiresAReason() throws Exception {
        Long id = createAccessRequest(idOf(EMPLOYEE_EMAIL), "HRMS", "payroll details");

        mockMvc.perform(post("/requests/" + id + "/reject")
                        .with(signedInAs(MANAGER_EMAIL))
                        .with(csrf())
                        .param("notes", ""))
                .andExpect(status().isOk())
                .andExpect(view().name("requests/detail"))
                .andExpect(content().string(Matchers.containsString("notes are required when rejecting")));

        AccessRequest stored = accessRequestRepository.findById(id).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(AccessRequest.Status.PENDING);
        assertThat(stored.getReviewedBy()).isNull();
    }

    @Test
    @DisplayName("A missing reason is refused exactly like a blank one")
    void rejectionWithoutTheFieldIsRefused() throws Exception {
        Long id = createAccessRequest(idOf(EMPLOYEE_EMAIL), "HRMS", "payroll details");

        mockMvc.perform(post("/requests/" + id + "/reject")
                        .with(signedInAs(MANAGER_EMAIL))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name("requests/detail"));

        assertThat(accessRequestRepository.findById(id).orElseThrow().getStatus())
                .isEqualTo(AccessRequest.Status.PENDING);
    }

    @Test
    @DisplayName("A refused decision keeps the reason the reviewer typed")
    void refusedDecisionKeepsTheNotes() throws Exception {
        Long id = createAccessRequest(idOf(EMPLOYEE_EMAIL), "HRMS", "payroll details");

        mockMvc.perform(post("/requests/" + id + "/reject")
                        .with(signedInAs(MANAGER_EMAIL))
                        .with(csrf())
                        .param("notes", "x".repeat(1001)))
                .andExpect(status().isOk())
                .andExpect(view().name("requests/detail"))
                .andExpect(content().string(
                        Matchers.containsString("notes must not exceed 1000 characters")));

        assertThat(accessRequestRepository.findById(id).orElseThrow().getStatus())
                .isEqualTo(AccessRequest.Status.PENDING);
    }

    @Test
    @DisplayName("A refused decision re-checks that the viewer may still read the request")
    void refusedDecisionStillChecksReadAccess() throws Exception {
        createEmployee("EMP-REVIEW-2", OTHER_EMPLOYEE_EMAIL);
        Long id = createAccessRequest(idOf(EMPLOYEE_EMAIL), "AWS", "not yours");

        // An employee cannot read the request, so the re-render of the detail page
        // is refused too rather than disclosing it.
        mockMvc.perform(post("/requests/" + id + "/reject")
                        .with(signedInAs(OTHER_EMPLOYEE_EMAIL))
                        .with(csrf())
                        .param("notes", "x".repeat(1001)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("An applicant cannot approve their own request, even as a manager")
    void selfApprovalIsRefused() throws Exception {
        Long managerId = idOf(MANAGER_EMAIL);
        Long id = createAccessRequest(managerId, "AWS", "I would like this myself");

        mockMvc.perform(post("/requests/" + id + "/approve")
                        .with(signedInAs(MANAGER_EMAIL))
                        .with(csrf())
                        .param("notes", "self"))
                .andExpect(status().isForbidden())
                .andExpect(view().name("error"))
                .andExpect(content().string(
                        Matchers.containsString("You cannot review your own access request")));

        AccessRequest stored = accessRequestRepository.findById(id).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(AccessRequest.Status.PENDING);
        assertThat(stored.getReviewedBy()).isNull();
    }

    @Test
    @DisplayName("An employee cannot decide somebody else's request")
    void employeeCannotDecide() throws Exception {
        createEmployee("EMP-REVIEW-3", OTHER_EMPLOYEE_EMAIL);
        Long id = createAccessRequest(idOf(EMPLOYEE_EMAIL), "AWS", "please");

        mockMvc.perform(post("/requests/" + id + "/approve")
                        .with(signedInAs(OTHER_EMPLOYEE_EMAIL))
                        .with(csrf()))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/requests/" + id + "/reject")
                        .with(signedInAs(OTHER_EMPLOYEE_EMAIL))
                        .with(csrf())
                        .param("notes", "no"))
                .andExpect(status().isForbidden());

        assertThat(accessRequestRepository.findById(id).orElseThrow().getStatus())
                .isEqualTo(AccessRequest.Status.PENDING);
    }

    @Test
    @DisplayName("Deciding a resolved request is a conflict page and the last decision stands")
    void secondDecisionIsAConflict() throws Exception {
        Long id = createAccessRequest(idOf(EMPLOYEE_EMAIL), "Jira", "sprint work");
        approveEveryStage(id);

        mockMvc.perform(post("/requests/" + id + "/approve")
                        .with(signedInAs(MANAGER_EMAIL))
                        .with(csrf())
                        .param("notes", "second"))
                .andExpect(status().isConflict())
                .andExpect(view().name("error"))
                .andExpect(content().string(Matchers.containsString("409")))
                .andExpect(content().string(Matchers.containsString("APPROVED")));

        AccessRequest stored = accessRequestRepository.findById(id).orElseThrow();
        assertThat(stored.getReviewNotes()).isEqualTo("stage 2 ok");
        assertThat(stored.getReviewedBy().getId()).isEqualTo(idOf(IT_ADMIN_EMAIL));
    }


    @Test
    @DisplayName("Deciding an unknown request is a 404 page")
    void unknownRequestIsANotFoundPage() throws Exception {
        mockMvc.perform(post("/requests/99999999/approve")
                        .with(signedInAs(MANAGER_EMAIL))
                        .with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(view().name("error"))
                .andExpect(content().string(Matchers.containsString("404")));
    }

    @Test
    @DisplayName("A decision without a CSRF token is refused and nothing is written")
    void decisionRequiresCsrf() throws Exception {
        Long id = createAccessRequest(idOf(EMPLOYEE_EMAIL), "AWS", "no token");

        mockMvc.perform(post("/requests/" + id + "/approve")
                        .with(signedInAs(MANAGER_EMAIL))
                        .param("notes", "forged"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/requests/" + id + "/reject")
                        .with(signedInAs(MANAGER_EMAIL))
                        .param("notes", "forged"))
                .andExpect(status().isForbidden());

        AccessRequest stored = accessRequestRepository.findById(id).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(AccessRequest.Status.PENDING);
        assertThat(stored.getReviewedBy()).isNull();
    }

    @Test
    @DisplayName("A decision cannot be attributed to somebody else")
    void reviewerComesFromTheSession() throws Exception {
        // Both stages are posted with somebody else's identity in the form. Neither
        // the request's reviewedBy nor a stage's decider may be taken from it: the
        // acting reviewer is whoever is signed in, at both stages.
        Long id = createAccessRequest(idOf(EMPLOYEE_EMAIL), "AWS", "who reviewed this");

        mockMvc.perform(post("/requests/" + id + "/approve")
                        .with(signedInAs(MANAGER_EMAIL))
                        .with(csrf())
                        .param("notes", "ok")
                        .param("reviewedBy", SUPER_ADMIN_EMAIL)
                        .param("reviewerId", String.valueOf(idOf(SUPER_ADMIN_EMAIL)))
                        .param("status", "APPROVED"))
                .andExpect(status().is3xxRedirection());

        mockMvc.perform(post("/requests/" + id + "/approve")
                        .with(signedInAs(IT_ADMIN_EMAIL))
                        .with(csrf())
                        .param("notes", "ok")
                        .param("reviewedBy", SUPER_ADMIN_EMAIL)
                        .param("reviewerId", String.valueOf(idOf(SUPER_ADMIN_EMAIL))))
                .andExpect(status().is3xxRedirection());

        AccessRequest stored = accessRequestRepository.findById(id).orElseThrow();
        assertThat(stored.getReviewedBy().getId()).isEqualTo(idOf(IT_ADMIN_EMAIL));
        assertThat(stored.getReviewedBy().getEmail()).isEqualTo(IT_ADMIN_EMAIL);
    }


    @Test
    @DisplayName("A reviewer's notes are escaped, never rendered as markup")
    void reviewNotesAreEscaped() throws Exception {
        Long id = createAccessRequest(idOf(EMPLOYEE_EMAIL), "AWS", "notes with markup");

        mockMvc.perform(post("/requests/" + id + "/reject")
                        .with(signedInAs(MANAGER_EMAIL))
                        .with(csrf())
                        .param("notes", "<script>alert('x')</script>"))
                .andExpect(status().is3xxRedirection());

        String page = mockMvc.perform(get("/requests/" + id).with(signedInAs(MANAGER_EMAIL)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(page).contains("&lt;script&gt;").doesNotContain("<script>alert");
    }

    @Test
    @DisplayName("Every reviewer role can take the stage that names it, and a SUPER_ADMIN can take either")
    void allReviewerRolesCanDecide() throws Exception {
        // Every reviewer role appears in the workflow, but not in every stage: a
        // MANAGER is stage 1, an IT_ADMIN is stage 2, and a SUPER_ADMIN may stand
        // in for either. Each case resolves the request and is recorded against
        // the role that actually posted the form.
        String[] firstStageReviewers = {MANAGER_EMAIL, SUPER_ADMIN_EMAIL};
        String[] secondStageReviewers = {IT_ADMIN_EMAIL, SUPER_ADMIN_EMAIL};
        String[] applications = {"App-0", "App-1", "App-2", "App-3"};

        for (int i = 0; i < firstStageReviewers.length; i++) {
            Long id = createAccessRequest(idOf(EMPLOYEE_EMAIL), applications[i], "request " + i);

            mockMvc.perform(post("/requests/" + id + "/approve")
                            .with(signedInAs(firstStageReviewers[i]))
                            .with(csrf())
                            .param("notes", "ok"))
                    .andExpect(status().is3xxRedirection());

            mockMvc.perform(post("/requests/" + id + "/approve")
                            .with(signedInAs(secondStageReviewers[i]))
                            .with(csrf())
                            .param("notes", "ok"))
                    .andExpect(status().is3xxRedirection());

            AccessRequest stored = accessRequestRepository.findById(id).orElseThrow();
            assertThat(stored.getStatus()).isEqualTo(AccessRequest.Status.APPROVED);
            assertThat(stored.getReviewedBy().getEmail()).isEqualTo(secondStageReviewers[i]);
        }
    }

    @Test
    @DisplayName("A reviewer cannot take a stage that names a different role")
    void reviewerCannotTakeTheWrongStage() throws Exception {
        // The page hides the forms from a reviewer who cannot act, but the POST
        // is what has to refuse it: a MANAGER posting stage 2 gets a 403 page and
        // nothing is written.
        Long id = createAccessRequest(idOf(EMPLOYEE_EMAIL), "AWS", "wrong stage");

        mockMvc.perform(post("/requests/" + id + "/approve")
                        .with(signedInAs(IT_ADMIN_EMAIL))
                        .with(csrf())
                        .param("notes", "not my stage"))
                .andExpect(status().isForbidden())
                .andExpect(view().name("error"));

        assertThat(accessRequestRepository.findById(id).orElseThrow().getStatus())
                .isEqualTo(AccessRequest.Status.PENDING);
    }


    @Test
    @DisplayName("The timestamped decision is what the applicant's list shows")
    void applicantSeesTheOutcome() throws Exception {
        Long id = createAccessRequest(idOf(EMPLOYEE_EMAIL), "GitHub", "need this");
        mockMvc.perform(post("/requests/" + id + "/reject")
                        .with(signedInAs(MANAGER_EMAIL))
                        .with(csrf())
                        .param("notes", "use the ticket system instead"))
                .andExpect(status().is3xxRedirection());

        mockMvc.perform(get("/requests")
                        .with(signedInAs(EMPLOYEE_EMAIL)))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("REJECTED")))
                .andExpect(content().string(Matchers.containsString("Anita Sharma")));
    }
}
