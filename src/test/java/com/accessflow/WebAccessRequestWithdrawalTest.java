package com.accessflow;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * Withdrawal and history in the browser.
 *
 * Two things are worth asserting beyond "the status changed". First, that the
 * form is drawn only for the applicant: showing a reviewer a control the service
 * refuses is a small lie about what they can do. Second, the withdrawn row in the
 * applicant's own list, which is where Phase 4 found a real bug.
 */
@DisplayName("Phase 4 - withdrawing and history in the browser")
@EnabledIfEnvironmentVariable(named = "ACCESSFLOW_DB_PASSWORD", matches = ".+")
class WebAccessRequestWithdrawalTest extends IntegrationTestSupport {

    private static final String OTHER_EMPLOYEE_EMAIL = "withdraw.other@accessflow.local";

    @Autowired
    private AccessRequestRepository accessRequestRepository;

    @Test
    @DisplayName("Withdrawing records the applicant, the time and the reason")
    void withdrawRecordsTheWithdrawal() throws Exception {
        Long id = createAccessRequest(idOf(EMPLOYEE_EMAIL), "GitHub", "no longer needed");

        mockMvc.perform(post("/requests/" + id + "/withdraw")
                        .with(signedInAs(EMPLOYEE_EMAIL))
                        .with(csrf())
                        .param("reason", "project postponed"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/requests/" + id));

        AccessRequest stored = accessRequestRepository.findById(id).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(AccessRequest.Status.WITHDRAWN);
        assertThat(stored.getWithdrawnAt()).isNotNull();
        assertThat(stored.getWithdrawalReason()).isEqualTo("project postponed");
        // No reviewer: a withdrawal had none, and recording one would misattribute
        // the applicant's decision.
        assertThat(stored.getReviewedBy()).isNull();
        assertThat(stored.getReviewedAt()).isNull();
        assertThat(stored.getReviewNotes()).isNull();
    }

    @Test
    @DisplayName("The reason is optional, so the form can be submitted empty")
    void withdrawWithoutAReason() throws Exception {
        Long id = createAccessRequest(idOf(EMPLOYEE_EMAIL), "Jira", "minding my own business");

        mockMvc.perform(post("/requests/" + id + "/withdraw")
                        .with(signedInAs(EMPLOYEE_EMAIL))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());

        AccessRequest stored = accessRequestRepository.findById(id).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(AccessRequest.Status.WITHDRAWN);
        assertThat(stored.getWithdrawalReason()).isNull();
    }

    @Test
    @DisplayName("An over-long reason re-renders the form and the request stays pending")
    void overLongReasonIsRefused() throws Exception {
        Long id = createAccessRequest(idOf(EMPLOYEE_EMAIL), "AWS", "too long to explain");

        mockMvc.perform(post("/requests/" + id + "/withdraw")
                        .with(signedInAs(EMPLOYEE_EMAIL))
                        .with(csrf())
                        .param("reason", "x".repeat(1001)))
                .andExpect(status().isOk())
                .andExpect(view().name("requests/detail"))
                .andExpect(content().string(
                        Matchers.containsString("reason must not exceed 1000 characters")));

        assertThat(accessRequestRepository.findById(id).orElseThrow().getStatus())
                .isEqualTo(AccessRequest.Status.PENDING);
    }

    @Test
    @DisplayName("A refused withdrawal re-checks that the viewer may still read the request")
    void refusedWithdrawalStillChecksReadAccess() throws Exception {
        createEmployee("EMP-WD-2", OTHER_EMPLOYEE_EMAIL);
        Long id = createAccessRequest(idOf(EMPLOYEE_EMAIL), "AWS", "not yours");

        // A refusal re-renders the detail page, and that re-render must not
        // disclose a request the viewer may not read.
        mockMvc.perform(post("/requests/" + id + "/withdraw")
                        .with(signedInAs(OTHER_EMPLOYEE_EMAIL))
                        .with(csrf())
                        .param("reason", "x".repeat(1001)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("The withdrawal form is offered to the applicant of a pending request")
    void formIsOfferedToTheApplicant() throws Exception {
        Long id = createAccessRequest(idOf(EMPLOYEE_EMAIL), "VPN", "travelling");

        mockMvc.perform(get("/requests/" + id).with(signedInAs(EMPLOYEE_EMAIL)))
                .andExpect(status().isOk())
                .andExpect(model().attribute("canWithdraw", true))
                .andExpect(content().string(Matchers.containsString("/withdraw")));
    }

    @Test
    @DisplayName("A reviewer looking at somebody else's request is offered no withdrawal form")
    void formIsHiddenFromReviewers() throws Exception {
        Long id = createAccessRequest(idOf(EMPLOYEE_EMAIL), "AWS", "not yours to take back");

        // The model flag and the rendered page must agree: presenting the control
        // would offer an action the service refuses.
        mockMvc.perform(get("/requests/" + id).with(signedInAs(MANAGER_EMAIL)))
                .andExpect(status().isOk())
                .andExpect(model().attribute("canWithdraw", false))
                .andExpect(content().string(Matchers.not(Matchers.containsString("/withdraw"))));
    }

    @Test
    @DisplayName("A withdrawn request offers no withdrawal form, being already resolved")
    void formIsHiddenOnceWithdrawn() throws Exception {
        Long id = createAccessRequest(idOf(EMPLOYEE_EMAIL), "Jira", "changed my mind");
        accessRequestService.withdrawRequest(id, idOf(EMPLOYEE_EMAIL), "no longer needed");

        mockMvc.perform(get("/requests/" + id).with(signedInAs(EMPLOYEE_EMAIL)))
                .andExpect(status().isOk())
                .andExpect(model().attribute("canWithdraw", false));
    }

    @Test
    @DisplayName("A decided request offers no withdrawal form")
    void formIsHiddenOnceDecided() throws Exception {
        Long id = createAccessRequest(idOf(EMPLOYEE_EMAIL), "AWS", "already decided");
        approveEveryStage(id);

        mockMvc.perform(get("/requests/" + id).with(signedInAs(EMPLOYEE_EMAIL)))
                .andExpect(status().isOk())
                .andExpect(model().attribute("canWithdraw", false));
    }

    @Test
    @DisplayName("An employee cannot withdraw another employee's request")
    void employeeCannotWithdrawAnotherRequest() throws Exception {
        createEmployee("EMP-WD-3", OTHER_EMPLOYEE_EMAIL);
        Long id = createAccessRequest(idOf(EMPLOYEE_EMAIL), "HRMS", "not yours");

        mockMvc.perform(post("/requests/" + id + "/withdraw")
                        .with(signedInAs(OTHER_EMPLOYEE_EMAIL))
                        .with(csrf()))
                .andExpect(status().isForbidden())
                .andExpect(view().name("error"));

        assertThat(accessRequestRepository.findById(id).orElseThrow().getStatus())
                .isEqualTo(AccessRequest.Status.PENDING);
    }

    @Test
    @DisplayName("A manager cannot withdraw another employee's request")
    void managerCannotWithdrawAnotherRequest() throws Exception {
        Long id = createAccessRequest(idOf(EMPLOYEE_EMAIL), "AWS", "applicant's request");

        mockMvc.perform(post("/requests/" + id + "/withdraw")
                        .with(signedInAs(MANAGER_EMAIL))
                        .with(csrf()))
                .andExpect(status().isForbidden());

        assertThat(accessRequestRepository.findById(id).orElseThrow().getStatus())
                .isEqualTo(AccessRequest.Status.PENDING);
    }

    @Test
    @DisplayName("Not even a super admin can withdraw another employee's request")
    void superAdminCannotWithdrawAnotherRequest() throws Exception {
        Long id = createAccessRequest(idOf(EMPLOYEE_EMAIL), "AWS", "applicant's request");

        mockMvc.perform(post("/requests/" + id + "/withdraw")
                        .with(signedInAs(SUPER_ADMIN_EMAIL))
                        .with(csrf()))
                .andExpect(status().isForbidden());

        assertThat(accessRequestRepository.findById(id).orElseThrow().getStatus())
                .isEqualTo(AccessRequest.Status.PENDING);
    }

    @Test
    @DisplayName("A manager can withdraw their own request")
    void managerCanWithdrawOwnRequest() throws Exception {
        Long id = createAccessRequest(idOf(MANAGER_EMAIL), "Jira", "raised by mistake");

        mockMvc.perform(post("/requests/" + id + "/withdraw")
                        .with(signedInAs(MANAGER_EMAIL))
                        .with(csrf())
                        .param("reason", "not needed after all"))
                .andExpect(status().is3xxRedirection());

        assertThat(accessRequestRepository.findById(id).orElseThrow().getStatus())
                .isEqualTo(AccessRequest.Status.WITHDRAWN);
    }

    @Test
    @DisplayName("A withdrawal form cannot be aimed at somebody else by adding fields to it")
    void withdrawalActorComesFromTheSession() throws Exception {
        Long id = createAccessRequest(idOf(EMPLOYEE_EMAIL), "GitHub", "mine only");

        mockMvc.perform(post("/requests/" + id + "/withdraw")
                        .with(signedInAs(EMPLOYEE_EMAIL))
                        .with(csrf())
                        .param("applicant", SUPER_ADMIN_EMAIL)
                        .param("applicantId", String.valueOf(idOf(SUPER_ADMIN_EMAIL)))
                        .param("status", "APPROVED"))
                .andExpect(status().is3xxRedirection());

        AccessRequest stored = accessRequestRepository.findById(id).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(AccessRequest.Status.WITHDRAWN);
        assertThat(stored.getApplicant().getId()).isEqualTo(idOf(EMPLOYEE_EMAIL));
    }

    @Test
    @DisplayName("A withdrawal without a CSRF token is refused and nothing is written")
    void withdrawalRequiresCsrf() throws Exception {
        Long id = createAccessRequest(idOf(EMPLOYEE_EMAIL), "AWS", "no token");

        mockMvc.perform(post("/requests/" + id + "/withdraw")
                        .with(signedInAs(EMPLOYEE_EMAIL))
                        .param("reason", "forged"))
                .andExpect(status().isForbidden());

        assertThat(accessRequestRepository.findById(id).orElseThrow().getStatus())
                .isEqualTo(AccessRequest.Status.PENDING);
    }

    @Test
    @DisplayName("A second withdrawal is a conflict page")
    void secondWithdrawalIsAConflict() throws Exception {
        Long id = createAccessRequest(idOf(EMPLOYEE_EMAIL), "Jira", "changed my mind twice");
        accessRequestService.withdrawRequest(id, idOf(EMPLOYEE_EMAIL), "first");

        mockMvc.perform(post("/requests/" + id + "/withdraw")
                        .with(signedInAs(EMPLOYEE_EMAIL))
                        .with(csrf())
                        .param("reason", "second"))
                .andExpect(status().isConflict())
                .andExpect(view().name("error"))
                .andExpect(content().string(Matchers.containsString("409")));

        assertThat(accessRequestRepository.findById(id).orElseThrow().getWithdrawalReason())
                .isEqualTo("first");
    }

    @Test
    @DisplayName("A decided request cannot then be withdrawn")
    void decidedRequestCannotBeWithdrawn() throws Exception {
        Long id = createAccessRequest(idOf(EMPLOYEE_EMAIL), "AWS", "approved already");
        approveEveryStage(id);

        mockMvc.perform(post("/requests/" + id + "/withdraw")
                        .with(signedInAs(EMPLOYEE_EMAIL))
                        .with(csrf()))
                .andExpect(status().isConflict());

        assertThat(accessRequestRepository.findById(id).orElseThrow().getStatus())
                .isEqualTo(AccessRequest.Status.APPROVED);
    }

    @Test
    @DisplayName("Withdrawing an unknown request is a 404 page")
    void unknownRequestIsANotFoundPage() throws Exception {
        mockMvc.perform(post("/requests/99999999/withdraw")
                        .with(signedInAs(EMPLOYEE_EMAIL))
                        .with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(view().name("error"))
                .andExpect(content().string(Matchers.containsString("404")));
    }

    @Test
    @DisplayName("Withdrawing while signed out is refused")
    void withdrawalRequiresSignIn() throws Exception {
        Long id = createAccessRequest(idOf(EMPLOYEE_EMAIL), "AWS", "anonymous attempt");

        mockMvc.perform(post("/requests/" + id + "/withdraw")
                        .with(csrf())
                        .param("reason", "anonymous"))
                .andExpect(status().is3xxRedirection());

        assertThat(accessRequestRepository.findById(id).orElseThrow().getStatus())
                .isEqualTo(AccessRequest.Status.PENDING);
    }

    @Test
    @DisplayName("The detail page shows the withdrawal and its reason")
    void detailPageShowsTheWithdrawal() throws Exception {
        Long id = createAccessRequest(idOf(EMPLOYEE_EMAIL), "VPN", "trip cancelled");
        accessRequestService.withdrawRequest(id, idOf(EMPLOYEE_EMAIL), "trip cancelled");

        mockMvc.perform(get("/requests/" + id).with(signedInAs(EMPLOYEE_EMAIL)))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("Withdrawn")))
                .andExpect(content().string(Matchers.containsString("trip cancelled")));
    }

    @Test
    @DisplayName("A withdrawn request says so in the applicant's list, not that it is awaiting a reviewer")
    void withdrawnRowIsNotDescribedAsWaiting() throws Exception {
        // This is the bug Phase 4 fixed. The Decision column fell back to "Waiting
        // for a reviewer" whenever reviewedAt was null, which is true of a
        // withdrawn request: a row nobody will ever review, described as one still
        // waiting. The fallback now excludes the withdrawn state.
        Long withdrawn = createAccessRequest(idOf(EMPLOYEE_EMAIL), "GitHub", "withdrawn one");
        accessRequestService.withdrawRequest(withdrawn, idOf(EMPLOYEE_EMAIL), "changed my mind");
        createAccessRequest(idOf(EMPLOYEE_EMAIL), "Jira", "still genuinely pending");

        mockMvc.perform(get("/requests").with(signedInAs(EMPLOYEE_EMAIL)))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("WITHDRAWN")))
                .andExpect(content().string(Matchers.containsString("Withdrawn by you")));
    }

    @Test
    @DisplayName("A withdrawn row and a pending row are described differently in the same list")
    void withdrawnAndPendingRowsDiffer() throws Exception {
        // The bug was a fallback keyed on the absence of a decision, so a list
        // holding both kinds of row is the case that would have shown it: one
        // waiting phrase for two rows, neither of which was waiting.
        Long withdrawn = createAccessRequest(idOf(EMPLOYEE_EMAIL), "GitHub", "withdrawn one");
        accessRequestService.withdrawRequest(withdrawn, idOf(EMPLOYEE_EMAIL), "changed my mind");
        createAccessRequest(idOf(EMPLOYEE_EMAIL), "Jira", "still pending");

        String page = mockMvc.perform(get("/requests").with(signedInAs(EMPLOYEE_EMAIL)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Exactly one of each phrase, so the two rows are described differently
        // rather than sharing the fallback.
        assertThat(page).contains("Withdrawn by you");
        assertThat(occurrences(page, "Waiting for a reviewer")).isEqualTo(1);
    }

    @Test
    @DisplayName("A withdrawn request does not say it is waiting for a reviewer")
    void withdrawnRowNeverSaysWaiting() throws Exception {
        Long withdrawn = createAccessRequest(idOf(EMPLOYEE_EMAIL), "GitHub", "withdrawn one");
        accessRequestService.withdrawRequest(withdrawn, idOf(EMPLOYEE_EMAIL), null);

        String page = mockMvc.perform(get("/requests").with(signedInAs(EMPLOYEE_EMAIL)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // A withdrawn-only list must contain the withdrawal and not the waiting
        // text. With both rows present the phrase could come from the pending one,
        // which is why this test withdraws everything.
        assertThat(page).contains("Withdrawn by you").doesNotContain("Waiting for a reviewer");
    }

    @Test
    @DisplayName("A pending request still says it is waiting for a reviewer")
    void pendingRowStillSaysWaiting() throws Exception {
        createAccessRequest(idOf(EMPLOYEE_EMAIL), "Jira", "genuinely waiting");

        mockMvc.perform(get("/requests").with(signedInAs(EMPLOYEE_EMAIL)))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("Waiting for a reviewer")))
                .andExpect(content().string(Matchers.not(Matchers.containsString("Withdrawn by you"))));
    }

    @Test
    @DisplayName("The detail page shows the history, oldest first")
    void detailPageShowsTheHistory() throws Exception {
        Long id = createAccessRequest(idOf(EMPLOYEE_EMAIL), "AWS", "history please");
        accessRequestService.withdrawRequest(id, idOf(EMPLOYEE_EMAIL), "changed my mind");

        mockMvc.perform(get("/requests/" + id).with(signedInAs(EMPLOYEE_EMAIL)))
                .andExpect(status().isOk())
                .andExpect(model().attribute("history", Matchers.hasSize(2)));

        String page = mockMvc.perform(get("/requests/" + id).with(signedInAs(EMPLOYEE_EMAIL)))
                .andReturn().getResponse().getContentAsString();

        assertThat(page).contains("History")
                .contains(">SUBMITTED<")
                .contains(">WITHDRAWN<")
                .contains("changed my mind");

        // Oldest first. Asserted on the model, not on the rendered string: the
        // status badge near the top of the page also says WITHDRAWN, so a
        // position comparison in the HTML would compare the badge against the
        // timeline rather than the two entries against each other.
        assertThat(historyActions(page))
                .containsExactly("SUBMITTED", "WITHDRAWN");
    }

    /**
     * The action badges inside the rendered history list, in document order.
     *
     * Scoped to the history section rather than the whole page, for two reasons:
     * the status badge at the top carries the same status name, and the approval
     * stages render as their own timeline of PENDING/APPROVED/REJECTED badges,
     * which are stages and not entries.
     */
    private static List<String> historyActions(String page) {
        int historyStart = page.indexOf("<h2>History</h2>");
        assertThat(historyStart).isPositive();

        int timelineStart = page.indexOf("class=\"timeline\"", historyStart);
        assertThat(timelineStart).isPositive();
        assertThat(timelineStart).isGreaterThan(historyStart);

        String timeline = page.substring(timelineStart);
        Matcher matcher = Pattern.compile(">([A-Z]+)</span>").matcher(timeline);

        List<String> actions = new ArrayList<>();
        while (matcher.find()) {
            actions.add(matcher.group(1));
        }

        return actions;
    }

    @Test
    @DisplayName("A reviewer sees the history too")
    void reviewerSeesTheHistory() throws Exception {
        Long id = createAccessRequest(idOf(EMPLOYEE_EMAIL), "AWS", "reviewer view");
        approveEveryStage(id);

        mockMvc.perform(get("/requests/" + id).with(signedInAs(MANAGER_EMAIL)))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("SUBMITTED")))
                .andExpect(content().string(Matchers.containsString("APPROVED")));
    }

    @Test
    @DisplayName("An employee still cannot reach the history through another employee's request")
    void historyObeysTheReadRule() throws Exception {
        createEmployee("EMP-WD-4", OTHER_EMPLOYEE_EMAIL);
        Long id = createAccessRequest(idOf(EMPLOYEE_EMAIL), "AWS", "not yours");

        mockMvc.perform(get("/requests/" + id).with(signedInAs(OTHER_EMPLOYEE_EMAIL)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("A withdrawal reason is escaped, never rendered as markup")
    void withdrawalReasonIsEscaped() throws Exception {
        Long id = createAccessRequest(idOf(EMPLOYEE_EMAIL), "GitHub", "notes with markup");
        accessRequestService.withdrawRequest(id, idOf(EMPLOYEE_EMAIL),
                "<script>alert('x')</script>");

        String page = mockMvc.perform(get("/requests/" + id).with(signedInAs(EMPLOYEE_EMAIL)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(page).contains("&lt;script&gt;").doesNotContain("<script>alert");
    }

    /**
     * Counts non-overlapping occurrences of a phrase, for the assertions that a
     * phrase appears once rather than at all. A "contains" check cannot tell one
     * row from two when both rows would render the same text.
     */
    private static int occurrences(String haystack, String needle) {
        int count = 0;
        int index = haystack.indexOf(needle);

        while (index >= 0) {
            count++;
            index = haystack.indexOf(needle, index + needle.length());
        }

        return count;
    }
}
