package com.accessflow;

import com.accessflow.dto.RequestHistoryEntry;
import com.accessflow.entity.User;
import com.accessflow.exception.InvalidAccessRequestStateException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 4 - the derived request history, extended by Phase 5's approval stages.
 *
 * The point of interest is that there is no event table. Every assertion here
 * would also pass against a history table, which is exactly why the tests check
 * the ordering, the actor and the shape of each entry rather than only the count:
 * those are the things a table would have to get right too.
 *
 * Phase 5 made the derivation harder rather than redundant. A request can now
 * hold a submission, two stage decisions and a withdrawal, so the tests here
 * check that each decided stage contributes exactly one entry, in stage order,
 * and that the derivation still needs nothing that is not already on the request
 * or on a stage row.
 */
@DisplayName("Phase 4 - request history API")
@EnabledIfEnvironmentVariable(named = "ACCESSFLOW_DB_PASSWORD", matches = ".+")
class AccessRequestHistoryApiTest extends IntegrationTestSupport {

    @Test
    @DisplayName("A pending request has exactly one entry: it was submitted")
    void pendingRequestHasOnlySubmission() throws Exception {
        Long id = createAccessRequest(idOf(EMPLOYEE_EMAIL), "GitHub", "need commit access");

        mockMvc.perform(get("/api/access-requests/" + id + "/history")
                        .with(httpBasic(EMPLOYEE_EMAIL, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size()").value(1))
                .andExpect(jsonPath("$[0].action").value("SUBMITTED"))
                .andExpect(jsonPath("$[0].at").exists())
                .andExpect(jsonPath("$[0].actor.email").value(EMPLOYEE_EMAIL))
                // The submission has no notes: there was nothing to say yet.
                .andExpect(jsonPath("$[0].notes").doesNotExist());
    }

    @Test
    @DisplayName("A withdrawn request has two entries, submission first")
    void withdrawnHistoryIsSubmissionThenWithdrawal() throws Exception {
        Long id = createAccessRequest(idOf(EMPLOYEE_EMAIL), "GitHub", "no longer needed");
        accessRequestService.withdrawRequest(id, idOf(EMPLOYEE_EMAIL), "project postponed");

        mockMvc.perform(get("/api/access-requests/" + id + "/history")
                        .with(httpBasic(EMPLOYEE_EMAIL, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size()").value(2))
                .andExpect(jsonPath("$[*].action", contains("SUBMITTED", "WITHDRAWN")))
                .andExpect(jsonPath("$[1].notes").value("project postponed"))
                // The applicant is the actor of both: they submitted it and they
                // took it back.
                .andExpect(jsonPath("$[1].actor.email").value(EMPLOYEE_EMAIL))
                .andExpect(jsonPath("$[1].actor.password").doesNotExist());
    }

    @Test
    @DisplayName("A withdrawal with no reason is still an entry")
    void withdrawnWithoutReasonStillHasTwoEntries() throws Exception {
        Long id = createAccessRequest(idOf(EMPLOYEE_EMAIL), "Jira", "minding my own business");
        accessRequestService.withdrawRequest(id, idOf(EMPLOYEE_EMAIL), null);

        mockMvc.perform(get("/api/access-requests/" + id + "/history")
                        .with(httpBasic(EMPLOYEE_EMAIL, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size()").value(2))
                .andExpect(jsonPath("$[1].action").value("WITHDRAWN"))
                .andExpect(jsonPath("$[1].notes").doesNotExist());
    }

    @Test
    @DisplayName("A fully approved request names the decider of each stage in turn")
    void approvedHistoryNamesTheReviewer() throws Exception {
        // Both approvals are entries, and the contract is unchanged: the same four
        // action values, the same record shape. A client that understood the
        // Phase 4 history reads this one correctly, it just sees one more
        // APPROVED.
        Long id = createAccessRequest(idOf(EMPLOYEE_EMAIL), "AWS", "quarter end close");
        approveEveryStage(id);

        mockMvc.perform(get("/api/access-requests/" + id + "/history")
                        .with(httpBasic(EMPLOYEE_EMAIL, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size()").value(3))
                .andExpect(jsonPath("$[*].action", contains("SUBMITTED", "APPROVED", "APPROVED")))
                .andExpect(jsonPath("$[0].actor.email").value(EMPLOYEE_EMAIL))
                .andExpect(jsonPath("$[1].actor.email").value(MANAGER_EMAIL))
                .andExpect(jsonPath("$[1].notes").value("stage 1 ok"))
                .andExpect(jsonPath("$[2].actor.email").value(IT_ADMIN_EMAIL))
                .andExpect(jsonPath("$[2].notes").value("stage 2 ok"));
    }

    @Test
    @DisplayName("A rejected request records the rejection and its reason")
    void rejectedHistoryRecordsTheReason() throws Exception {
        // A rejection ends the request at whatever stage it lands. A MANAGER
        // rejecting at stage 1 leaves one decision; the same MANAGER could not
        // reject at stage 2, which is IT_ADMIN's, so the test uses the reviewer
        // the stage actually asks for.
        Long id = createAccessRequest(idOf(EMPLOYEE_EMAIL), "HRMS", "payroll details");
        accessRequestService.rejectRequest(id, idOf(MANAGER_EMAIL), "not your department");

        mockMvc.perform(get("/api/access-requests/" + id + "/history")
                        .with(httpBasic(EMPLOYEE_EMAIL, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].action", contains("SUBMITTED", "REJECTED")))
                .andExpect(jsonPath("$[1].actor.email").value(MANAGER_EMAIL))
                .andExpect(jsonPath("$[1].notes").value("not your department"));
    }

    @Test
    @DisplayName("A request approved and then rejected at stage 2 records both, in order")
    void approvalThenRejectionIsBothRecorded() throws Exception {
        // The case a single-decision history could not express, and the reason the
        // derivation had to be widened rather than a table introduced. It is still
        // derived: an approval on stage 1 and a rejection on stage 2, nothing else.
        Long id = createAccessRequest(idOf(EMPLOYEE_EMAIL), "AWS", "approved then blocked");
        approveFirstStageOnly(id);
        accessRequestService.rejectRequest(id, idOf(IT_ADMIN_EMAIL), "policy blocks this");

        mockMvc.perform(get("/api/access-requests/" + id + "/history")
                        .with(httpBasic(EMPLOYEE_EMAIL, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size()").value(3))
                .andExpect(jsonPath("$[*].action", contains("SUBMITTED", "APPROVED", "REJECTED")))
                .andExpect(jsonPath("$[1].actor.email").value(MANAGER_EMAIL))
                .andExpect(jsonPath("$[2].actor.email").value(IT_ADMIN_EMAIL))
                .andExpect(jsonPath("$[2].notes").value("policy blocks this"));
    }

    @Test
    @DisplayName("A withdrawal after the first approval is recorded after it, not instead of it")
    void withdrawalAfterAStageApproval() throws Exception {
        // The applicant may still withdraw while the request is unresolved, and
        // the approval that already happened is not erased by it. That is the
        // point: a withdrawal closes the request, it does not rewrite it.
        Long id = createAccessRequest(idOf(EMPLOYEE_EMAIL), "AWS", "approved then changed my mind");
        approveFirstStageOnly(id);
        accessRequestService.withdrawRequest(id, idOf(EMPLOYEE_EMAIL), "no longer needed");

        mockMvc.perform(get("/api/access-requests/" + id + "/history")
                        .with(httpBasic(EMPLOYEE_EMAIL, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].action",
                        contains("SUBMITTED", "APPROVED", "WITHDRAWN")))
                .andExpect(jsonPath("$[1].actor.email").value(MANAGER_EMAIL))
                .andExpect(jsonPath("$[2].actor.email").value(EMPLOYEE_EMAIL))
                .andExpect(jsonPath("$[2].notes").value("no longer needed"));
    }

    @Test
    @DisplayName("Entries are ordered oldest first regardless of who reads them")
    void entriesAreOldestFirst() throws Exception {
        Long id = createAccessRequest(idOf(EMPLOYEE_EMAIL), "VPN", "traveling");
        accessRequestService.approveRequest(id, idOf(MANAGER_EMAIL), "granted");

        // The same history for the applicant and for a reviewer, so the order
        // cannot depend on the reader.
        for (String email : new String[]{EMPLOYEE_EMAIL, MANAGER_EMAIL}) {
            mockMvc.perform(get("/api/access-requests/" + id + "/history")
                            .with(httpBasic(email, PASSWORD)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].action").value("SUBMITTED"))
                    .andExpect(jsonPath("$[1].action").value("APPROVED"));
        }
    }

    @Test
    @DisplayName("The history of a withdrawn request is still readable after the withdrawal")
    void withdrawnHistoryRemainsReadable() throws Exception {
        // Withdrawal is not a deletion and not a sealing of the record: the
        // applicant and reviewers still need to see why the request stopped.
        Long id = createAccessRequest(idOf(EMPLOYEE_EMAIL), "AWS", "closed out");
        accessRequestService.withdrawRequest(id, idOf(EMPLOYEE_EMAIL), "project cancelled");

        for (String email : new String[]{EMPLOYEE_EMAIL, MANAGER_EMAIL, SUPER_ADMIN_EMAIL}) {
            mockMvc.perform(get("/api/access-requests/" + id + "/history")
                            .with(httpBasic(email, PASSWORD)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.size()").value(2))
                    .andExpect(jsonPath("$[1].action").value("WITHDRAWN"))
                    .andExpect(jsonPath("$[1].notes").value("project cancelled"));
        }
    }

    @Test
    @DisplayName("The applicant may read the history of their own request")
    void applicantMayReadOwnHistory() throws Exception {
        Long id = createAccessRequest(idOf(EMPLOYEE_EMAIL), "AWS", "mine");

        mockMvc.perform(get("/api/access-requests/" + id + "/history")
                        .with(httpBasic(EMPLOYEE_EMAIL, PASSWORD)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Every reviewer role may read a history")
    void reviewersMayReadHistory() throws Exception {
        Long id = createAccessRequest(idOf(EMPLOYEE_EMAIL), "AWS", "reviewable");

        for (String email : new String[]{MANAGER_EMAIL, IT_ADMIN_EMAIL, SUPER_ADMIN_EMAIL}) {
            mockMvc.perform(get("/api/access-requests/" + id + "/history")
                            .with(httpBasic(email, PASSWORD)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.size()").value(1));
        }
    }

    @Test
    @DisplayName("Another employee is forbidden from reading a history")
    void employeeCannotReadAnotherHistory() throws Exception {
        Long id = createAccessRequest(idOf(EMPLOYEE_EMAIL), "AWS", "not yours");
        createUser("EMP-2ND", "actor.employee2@accessflow.local", User.Role.EMPLOYEE);

        mockMvc.perform(get("/api/access-requests/" + id + "/history")
                        .with(httpBasic("actor.employee2@accessflow.local", PASSWORD)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    @DisplayName("History is not a way around the read rule on the request itself")
    void historyCannotBeReadWhenTheRequestCannot() throws Exception {
        // Both endpoints apply the same rule, so if the request is refused the
        // history must be too. If this ever passes while
        // AccessRequestApiTest.employeeCannotReadAnotherRequest passes as well,
        // the two have drifted apart.
        Long id = createAccessRequest(idOf(EMPLOYEE_EMAIL), "AWS", "not yours");
        createUser("EMP-2ND", "actor.employee2@accessflow.local", User.Role.EMPLOYEE);

        mockMvc.perform(get("/api/access-requests/" + id)
                        .with(httpBasic("actor.employee2@accessflow.local", PASSWORD)))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/access-requests/" + id + "/history")
                        .with(httpBasic("actor.employee2@accessflow.local", PASSWORD)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("An unknown request has no history, so it is a 404")
    void unknownRequestHasNoHistory() throws Exception {
        mockMvc.perform(get("/api/access-requests/99999999/history")
                        .with(httpBasic(MANAGER_EMAIL, PASSWORD)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.path").value("/api/access-requests/99999999/history"));
    }

    @Test
    @DisplayName("Reading a history without authentication is refused")
    void historyRequiresAuthentication() throws Exception {
        Long id = createAccessRequest(idOf(EMPLOYEE_EMAIL), "AWS", "anonymous attempt");

        mockMvc.perform(get("/api/access-requests/" + id + "/history"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("No history entry carries a password, a hash or the applicant's credentials")
    void historyNeverCarriesSecrets() throws Exception {
        Long id = createAccessRequest(idOf(EMPLOYEE_EMAIL), "AWS", "checking for leaks");
        accessRequestService.approveRequest(id, idOf(MANAGER_EMAIL), "granted");

        String body = mockMvc.perform(get("/api/access-requests/" + id + "/history")
                        .with(httpBasic(EMPLOYEE_EMAIL, PASSWORD)))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("password")
                .doesNotContain(PASSWORD)
                .doesNotContain("$2a$")
                .doesNotContain("$2b$");
    }

    @Test
    @DisplayName("The history of a resolved request stops growing, whatever is tried on it")
    void historyIsBoundedByTheTerminalTransition() throws Exception {
        // Still the reason there is no event table. A resolved request refuses
        // every further transition, so the derivation cannot produce a new entry
        // however hard it is tried: the bound is the terminal status, not a cap
        // on the list. Two stages plus a submission, and no more.
        Long id = createAccessRequest(idOf(EMPLOYEE_EMAIL), "AWS", "resolve me");
        Long reviewerId = idOf(IT_ADMIN_EMAIL);
        Long applicantId = idOf(EMPLOYEE_EMAIL);

        approveEveryStage(id);

        // Every transition that could have been tried, and all are refused.
        assertThat(tryApprove(id, reviewerId)).isFalse();
        assertThat(tryReject(id, reviewerId)).isFalse();
        assertThat(tryWithdraw(id, applicantId)).isFalse();

        assertThat(accessRequestService.getRequestHistory(id, applicantId))
                .hasSize(3)
                .extracting(RequestHistoryEntry::action)
                .containsExactly(RequestHistoryEntry.Action.SUBMITTED,
                        RequestHistoryEntry.Action.APPROVED,
                        RequestHistoryEntry.Action.APPROVED);
    }

    /**
     * Attempts a transition and reports whether it was allowed, so the test can
     * assert that all three are refused without stopping at the first exception.
     */
    private boolean tryApprove(Long id, Long reviewerId) {
        try {
            accessRequestService.approveRequest(id, reviewerId, "again");
            return true;
        } catch (InvalidAccessRequestStateException refused) {
            return false;
        }
    }

    private boolean tryReject(Long id, Long reviewerId) {
        try {
            accessRequestService.rejectRequest(id, reviewerId, "again");
            return true;
        } catch (InvalidAccessRequestStateException refused) {
            return false;
        }
    }

    private boolean tryWithdraw(Long id, Long applicantId) {
        try {
            accessRequestService.withdrawRequest(id, applicantId, "again");
            return true;
        } catch (InvalidAccessRequestStateException refused) {
            return false;
        }
    }
}
