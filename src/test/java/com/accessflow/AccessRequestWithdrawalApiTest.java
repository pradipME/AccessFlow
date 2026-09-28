package com.accessflow;

import com.accessflow.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 4 - the withdrawal endpoint.
 *
 * The rules under test are that only the applicant may withdraw, that the reason
 * is optional, and that a withdrawal is terminal. The last one is the reason this
 * class is more than a wrapper around a single call: WITHDRAWN has to behave like
 * APPROVED and REJECTED in every direction, and the cheapest way to be sure is to
 * try every transition against a withdrawn row.
 */
@DisplayName("Phase 4 - withdrawal API")
@EnabledIfEnvironmentVariable(named = "ACCESSFLOW_DB_PASSWORD", matches = ".+")
class AccessRequestWithdrawalApiTest extends IntegrationTestSupport {

    @Test
    @DisplayName("PATCH withdraw moves a pending request to WITHDRAWN and records when and why")
    void withdrawEndpoint() throws Exception {
        Long id = createAccessRequest(idOf(EMPLOYEE_EMAIL), "GitHub", "no longer needed");

        mockMvc.perform(patch("/api/access-requests/" + id + "/withdraw")
                        .with(httpBasic(EMPLOYEE_EMAIL, PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("reason", "project postponed")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WITHDRAWN"))
                .andExpect(jsonPath("$.withdrawalReason").value("project postponed"))
                .andExpect(jsonPath("$.withdrawnAt").exists())
                // A withdrawal had no reviewer, so a reviewer must not appear on
                // it. Writing one here would misattribute the applicant's decision.
                .andExpect(jsonPath("$.reviewedBy").doesNotExist())
                .andExpect(jsonPath("$.reviewedAt").doesNotExist())
                .andExpect(jsonPath("$.reviewNotes").doesNotExist());
    }

    @Test
    @DisplayName("The reason is optional, and the request is then withdrawn with none recorded")
    void withdrawWithoutABody() throws Exception {
        Long id = createAccessRequest(idOf(EMPLOYEE_EMAIL), "Jira", "minding my own business");

        mockMvc.perform(patch("/api/access-requests/" + id + "/withdraw")
                        .with(httpBasic(EMPLOYEE_EMAIL, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WITHDRAWN"))
                .andExpect(jsonPath("$.withdrawnAt").exists())
                .andExpect(jsonPath("$.withdrawalReason").doesNotExist());
    }

    @Test
    @DisplayName("An empty reason is accepted, being the same as no reason")
    void withdrawWithAnEmptyReason() throws Exception {
        Long id = createAccessRequest(idOf(EMPLOYEE_EMAIL), "AWS", "changed my mind");

        mockMvc.perform(patch("/api/access-requests/" + id + "/withdraw")
                        .with(httpBasic(EMPLOYEE_EMAIL, PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("reason", "")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WITHDRAWN"));
    }

    @Test
    @DisplayName("An over-long reason is a 400 and leaves the request pending")
    void withdrawRejectsAnOverLongReason() throws Exception {
        Long id = createAccessRequest(idOf(EMPLOYEE_EMAIL), "HRMS", "too long to explain");

        mockMvc.perform(patch("/api/access-requests/" + id + "/withdraw")
                        .with(httpBasic(EMPLOYEE_EMAIL, PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("reason", "x".repeat(1001))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors[?(@.field == 'reason')]").exists());

        // The request must still be withdrawable afterwards: a refused withdrawal
        // is not a partial one.
        mockMvc.perform(get("/api/access-requests/" + id).with(httpBasic(EMPLOYEE_EMAIL, PASSWORD)))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    @DisplayName("A reason of exactly 1000 characters is accepted")
    void withdrawAcceptsTheBoundaryLengthReason() throws Exception {
        Long id = createAccessRequest(idOf(EMPLOYEE_EMAIL), "VPN", "exactly at the limit");

        mockMvc.perform(patch("/api/access-requests/" + id + "/withdraw")
                        .with(httpBasic(EMPLOYEE_EMAIL, PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("reason", "y".repeat(1000))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WITHDRAWN"));
    }

    @Test
    @DisplayName("A body cannot name somebody else as the applicant of the withdrawal")
    void withdrawalApplicantComesFromTheAuthentication() throws Exception {
        Long employeeId = idOf(EMPLOYEE_EMAIL);
        Long managerId = idOf(MANAGER_EMAIL);
        Long id = createAccessRequest(employeeId, "GitHub", "mine to withdraw");

        // Both a nested applicant and a flat applicantId, as the create endpoint
        // is tested. Neither is bound: the body has no such field, so the only
        // applicant possible is whoever is signed in.
        mockMvc.perform(patch("/api/access-requests/" + id + "/withdraw")
                        .with(httpBasic(EMPLOYEE_EMAIL, PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("reason", "mine", "applicantId", String.valueOf(managerId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WITHDRAWN"))
                .andExpect(jsonPath("$.applicant.id").value(employeeId));
    }

    @Test
    @DisplayName("An employee cannot withdraw another employee's request")
    void employeeCannotWithdrawAnotherRequest() throws Exception {
        Long id = createAccessRequest(idOf(MANAGER_EMAIL), "AWS", "not yours to take back");
        createUser("EMP-2ND", "actor.employee2@accessflow.local", User.Role.EMPLOYEE);

        mockMvc.perform(patch("/api/access-requests/" + id + "/withdraw")
                        .with(httpBasic("actor.employee2@accessflow.local", PASSWORD)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));

        // Untouched, so the refusal really was a refusal.
        mockMvc.perform(get("/api/access-requests/" + id).with(httpBasic(MANAGER_EMAIL, PASSWORD)))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    @DisplayName("Not even a SUPER_ADMIN can withdraw another employee's request")
    void superAdminCannotWithdrawAnotherRequest() throws Exception {
        // A reviewer has approve and reject, which is the stronger action and one
        // that can explain itself. A note-free way to close another person's
        // request would leave the applicant nothing to read.
        Long id = createAccessRequest(idOf(EMPLOYEE_EMAIL), "AWS", "applicant's request");

        mockMvc.perform(patch("/api/access-requests/" + id + "/withdraw")
                        .with(httpBasic(SUPER_ADMIN_EMAIL, PASSWORD)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    @DisplayName("A reviewer can withdraw their own request")
    void reviewerCanWithdrawOwnRequest() throws Exception {
        // The rule is about ownership, not about role. A MANAGER filing a request
        // may change their mind like anybody else.
        Long id = createAccessRequest(idOf(MANAGER_EMAIL), "Jira", "I would like this myself");

        mockMvc.perform(patch("/api/access-requests/" + id + "/withdraw")
                        .with(httpBasic(MANAGER_EMAIL, PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("reason", "raised by mistake")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WITHDRAWN"));
    }

    @Test
    @DisplayName("A withdrawn request cannot be withdrawn again")
    void doubleWithdrawalIsConflict() throws Exception {
        Long id = createAccessRequest(idOf(EMPLOYEE_EMAIL), "GitHub", "changed my mind twice");
        accessRequestService.withdrawRequest(id, idOf(EMPLOYEE_EMAIL), "first");

        mockMvc.perform(patch("/api/access-requests/" + id + "/withdraw")
                        .with(httpBasic(EMPLOYEE_EMAIL, PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("reason", "second")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("WITHDRAWN")));

        // The first reason survives the refused second attempt.
        mockMvc.perform(get("/api/access-requests/" + id).with(httpBasic(EMPLOYEE_EMAIL, PASSWORD)))
                .andExpect(jsonPath("$.withdrawalReason").value("first"));
    }

    @Test
    @DisplayName("A withdrawn request can no longer be approved")
    void withdrawnCannotBeApproved() throws Exception {
        Long id = createAccessRequest(idOf(EMPLOYEE_EMAIL), "AWS", "withdrawn then approved");
        accessRequestService.withdrawRequest(id, idOf(EMPLOYEE_EMAIL), null);

        mockMvc.perform(patch("/api/access-requests/" + id + "/approve")
                        .with(httpBasic(MANAGER_EMAIL, PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("notes", "too late")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    @DisplayName("A withdrawn request can no longer be rejected")
    void withdrawnCannotBeRejected() throws Exception {
        Long id = createAccessRequest(idOf(EMPLOYEE_EMAIL), "AWS", "withdrawn then rejected");
        accessRequestService.withdrawRequest(id, idOf(EMPLOYEE_EMAIL), null);

        mockMvc.perform(patch("/api/access-requests/" + id + "/reject")
                        .with(httpBasic(MANAGER_EMAIL, PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("notes", "too late")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    @DisplayName("An approved request can no longer be withdrawn")
    void approvedCannotBeWithdrawn() throws Exception {
        Long id = createAccessRequest(idOf(EMPLOYEE_EMAIL), "AWS", "approved then withdrawn");
        approveEveryStage(id);

        mockMvc.perform(patch("/api/access-requests/" + id + "/withdraw")
                        .with(httpBasic(EMPLOYEE_EMAIL, PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("reason", "actually no")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("APPROVED")));
    }

    @Test
    @DisplayName("A rejected request can no longer be withdrawn")
    void rejectedCannotBeWithdrawn() throws Exception {
        Long id = createAccessRequest(idOf(EMPLOYEE_EMAIL), "AWS", "rejected then withdrawn");
        accessRequestService.rejectRequest(id, idOf(MANAGER_EMAIL), "no need");

        mockMvc.perform(patch("/api/access-requests/" + id + "/withdraw")
                        .with(httpBasic(EMPLOYEE_EMAIL, PASSWORD)))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("Withdrawing an unknown request returns 404")
    void withdrawUnknownRequestIs404() throws Exception {
        mockMvc.perform(patch("/api/access-requests/99999999/withdraw")
                        .with(httpBasic(EMPLOYEE_EMAIL, PASSWORD)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    @DisplayName("A reviewer cannot withdraw somebody else's request by naming themselves in the body")
    void withdrawalActorComesFromTheAuthentication() throws Exception {
        // The strongest form of the rule: even a SUPER_ADMIN signing their own
        // request cannot close it through somebody else's ownership. The
        // withdrawal is the applicant's and nobody else's.
        Long employeeId = idOf(EMPLOYEE_EMAIL);
        Long id = createAccessRequest(employeeId, "AWS", "applicant's request");
        createUser("EMP-2ND", "actor.employee2@accessflow.local", User.Role.SUPER_ADMIN);

        mockMvc.perform(patch("/api/access-requests/" + id + "/withdraw")
                        .with(httpBasic("actor.employee2@accessflow.local", PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("reason", "pretending", "applicant", EMPLOYEE_EMAIL)))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/access-requests/" + id).with(httpBasic(EMPLOYEE_EMAIL, PASSWORD)))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    @DisplayName("Withdrawing without authentication is refused")
    void withdrawRequiresAuthentication() throws Exception {
        Long id = createAccessRequest(idOf(EMPLOYEE_EMAIL), "AWS", "anonymous attempt");

        mockMvc.perform(patch("/api/access-requests/" + id + "/withdraw"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("A refused withdrawal on somebody else's request is 403 even when the request is resolved")
    void nonOwnerIsRefusedBeforeTheStateIsReported() throws Exception {
        // The check order is 404, then 403, then 409. A non-owner must not be able
        // to discover that a request is already decided by reading a 409 instead
        // of a 403: that is the one thing about somebody else's request they
        // should not learn.
        Long id = createAccessRequest(idOf(EMPLOYEE_EMAIL), "AWS", "already approved");
        approveEveryStage(id);
        createUser("EMP-2ND", "actor.employee2@accessflow.local", User.Role.EMPLOYEE);

        mockMvc.perform(patch("/api/access-requests/" + id + "/withdraw")
                        .with(httpBasic("actor.employee2@accessflow.local", PASSWORD)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    @DisplayName("A withdrawn request disappears from nothing and reappears nowhere as pending")
    void withdrawnLeavesThePendingQueue() throws Exception {
        Long id = createAccessRequest(idOf(EMPLOYEE_EMAIL), "GitHub", "leaving the queue");
        accessRequestService.withdrawRequest(id, idOf(EMPLOYEE_EMAIL), "no longer needed");

        mockMvc.perform(get("/api/access-requests/status/PENDING")
                        .with(httpBasic(MANAGER_EMAIL, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.hasItem(id.intValue()))));

        // Still visible to its owner, and still reported as withdrawn.
        mockMvc.perform(get("/api/access-requests/mine").with(httpBasic(EMPLOYEE_EMAIL, PASSWORD)))
                .andExpect(jsonPath("$[0].status").value("WITHDRAWN"));
    }

    @Test
    @DisplayName("No withdrawal response contains a password or hash")
    void noPasswordIsEverReturned() throws Exception {
        Long id = createAccessRequest(idOf(EMPLOYEE_EMAIL), "AWS", "checking for leaks");

        String body = mockMvc.perform(patch("/api/access-requests/" + id + "/withdraw")
                        .with(httpBasic(EMPLOYEE_EMAIL, PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("reason", "checking for leaks too")))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("password")
                .doesNotContain(PASSWORD)
                .doesNotContain("$2a$")
                .doesNotContain("$2b$");
    }
}
