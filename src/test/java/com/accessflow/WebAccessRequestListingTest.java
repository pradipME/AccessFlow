package com.accessflow;

import com.accessflow.entity.AccessRequest;
import com.accessflow.entity.User;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@DisplayName("Phase 3 - reading requests in the browser")
@EnabledIfEnvironmentVariable(named = "ACCESSFLOW_DB_PASSWORD", matches = ".+")
class WebAccessRequestListingTest extends IntegrationTestSupport {

    private static final String OTHER_EMPLOYEE_EMAIL = "listing.other@accessflow.local";

    @Test
    @DisplayName("My requests lists the caller's requests and nobody else's")
    void myRequestsIsScopedToTheCaller() throws Exception {
        Long mine = createAccessRequest(idOf(EMPLOYEE_EMAIL), "GitHub", "mine");
        createAccessRequest(idOf(MANAGER_EMAIL), "Jira", "not mine");

        mockMvc.perform(get("/requests").with(signedInAs(EMPLOYEE_EMAIL)))
                .andExpect(status().isOk())
                .andExpect(view().name("requests/mine"))
                .andExpect(content().string(Matchers.containsString("GitHub")))
                .andExpect(content().string(Matchers.not(Matchers.containsString("not mine"))))
                .andExpect(content().string(Matchers.containsString("/requests/" + mine)));
    }

    @Test
    @DisplayName("A caller with no requests gets an explanation rather than an empty table")
    void emptyListIsExplained() throws Exception {
        mockMvc.perform(get("/requests").with(signedInAs(EMPLOYEE_EMAIL)))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("You have not asked for anything yet.")));
    }

    @Test
    @DisplayName("The detail page shows the request to its applicant")
    void applicantCanOpenTheDetailPage() throws Exception {
        Long id = createAccessRequest(idOf(EMPLOYEE_EMAIL), "VPN", "Travelling next week");

        mockMvc.perform(get("/requests/" + id).with(signedInAs(EMPLOYEE_EMAIL)))
                .andExpect(status().isOk())
                .andExpect(view().name("requests/detail"))
                .andExpect(content().string(Matchers.containsString("VPN")))
                .andExpect(content().string(Matchers.containsString("Travelling next week")))
                .andExpect(content().string(Matchers.containsString("PENDING")));
    }

    @Test
    @DisplayName("Every reviewer role can open anybody's request")
    void reviewersCanOpenAnyRequest() throws Exception {
        Long id = createAccessRequest(idOf(EMPLOYEE_EMAIL), "AWS", "readable by a manager");

        for (String reviewer : new String[]{MANAGER_EMAIL, IT_ADMIN_EMAIL, SUPER_ADMIN_EMAIL}) {
            mockMvc.perform(get("/requests/" + id).with(signedInAs(reviewer)))
                    .andExpect(status().isOk())
                    .andExpect(content().string(Matchers.containsString("AWS")));
        }
    }

    @Test
    @DisplayName("An employee cannot open another employee's request")
    void employeeCannotOpenAnotherRequest() throws Exception {
        createEmployee("EMP-LIST-2", OTHER_EMPLOYEE_EMAIL);
        Long id = createAccessRequest(idOf(EMPLOYEE_EMAIL), "HRMS", "not yours");

        mockMvc.perform(get("/requests/" + id).with(signedInAs(OTHER_EMPLOYEE_EMAIL)))
                .andExpect(status().isForbidden())
                .andExpect(view().name("error"))
                .andExpect(content().string(Matchers.containsString("403")))
                .andExpect(content().string(Matchers.containsString("not permitted to read")));
    }

    @Test
    @DisplayName("An unknown request id is a 404 page")
    void unknownRequestIsANotFoundPage() throws Exception {
        mockMvc.perform(get("/requests/99999999").with(signedInAs(MANAGER_EMAIL)))
                .andExpect(status().isNotFound())
                .andExpect(view().name("error"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(Matchers.containsString("404")));
    }

    @Test
    @DisplayName("A missing static file is a 404 page, not a stack trace")
    void missingStaticResourceIsANotFoundPage() throws Exception {
        mockMvc.perform(get("/css/not-a-real-file.css"))
                .andExpect(status().isNotFound())
                .andExpect(view().name("error"));
    }

    @Test
    @DisplayName("The stylesheet is served to an anonymous visitor")
    void stylesheetIsPublic() throws Exception {
        mockMvc.perform(get("/css/accessflow.css"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("An employee is refused the review queue")
    void employeeCannotSeeTheReviewQueue() throws Exception {
        // Refused inside the filter chain, before the DispatcherServlet, so there
        // is no ModelAndView to name: the chain resolves and renders the page.
        // The chain is the first gate; the controller repeats the rule for
        // anything that reaches it, so a page can never widen what the chain
        // allows.
        mockMvc.perform(get("/requests/review").with(signedInAs(EMPLOYEE_EMAIL)))
                .andExpect(status().isForbidden())
                .andExpect(content().string(Matchers.containsString("403")))
                .andExpect(content().string(Matchers.containsString("Forbidden")))
                .andExpect(content().string(
                        Matchers.containsString("You do not have permission to perform this action")));
    }

    @Test
    @DisplayName("Every reviewer role sees the whole queue")
    void reviewersSeeTheWholeQueue() throws Exception {
        createAccessRequest(idOf(EMPLOYEE_EMAIL), "GitHub", "one");
        createAccessRequest(idOf(MANAGER_EMAIL), "Jira", "two");

        for (String reviewer : new String[]{MANAGER_EMAIL, IT_ADMIN_EMAIL, SUPER_ADMIN_EMAIL}) {
            mockMvc.perform(get("/requests/review").with(signedInAs(reviewer)))
                    .andExpect(status().isOk())
                    .andExpect(view().name("requests/review"))
                    .andExpect(content().string(Matchers.containsString("GitHub")))
                    .andExpect(content().string(Matchers.containsString("Jira")));
        }
    }

    @Test
    @DisplayName("The queue can be narrowed to one status")
    void queueCanBeFilteredByStatus() throws Exception {
        Long pending = createAccessRequest(idOf(EMPLOYEE_EMAIL), "GitHub", "still waiting");
        Long approved = createAccessRequest(idOf(EMPLOYEE_EMAIL), "Jira", "to be approved");
        approveEveryStage(approved);

        mockMvc.perform(get("/requests/review").param("status", "PENDING")
                        .with(signedInAs(MANAGER_EMAIL)))
                .andExpect(status().isOk())
                .andExpect(model().attribute("selectedStatus", AccessRequest.Status.PENDING))
                .andExpect(content().string(Matchers.containsString("GitHub")))
                .andExpect(content().string(Matchers.not(Matchers.containsString("Jira"))));

        mockMvc.perform(get("/requests/review").param("status", "APPROVED")
                        .with(signedInAs(MANAGER_EMAIL)))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("Jira")));

        assertThat(pending).isNotEqualTo(approved);
    }

    @Test
    @DisplayName("An unusable status filter is a 400 page rather than a server fault")
    void unknownStatusIsABadRequestPage() throws Exception {
        mockMvc.perform(get("/requests/review").param("status", "BANANA")
                        .with(signedInAs(MANAGER_EMAIL)))
                .andExpect(status().isBadRequest())
                .andExpect(view().name("error"))
                .andExpect(content().string(Matchers.containsString("400")));
    }

    @Test
    @DisplayName("A request id that is not a number is a 404 page and never reaches the workflow")
    void nonNumericIdIsANotFoundPage() throws Exception {
        // The route is declared as {id:\d+}, so "not-a-number" is not a request id
        // at all and never reaches the service.
        mockMvc.perform(get("/requests/not-a-number").with(signedInAs(MANAGER_EMAIL)))
                .andExpect(status().isNotFound())
                .andExpect(view().name("error"));
    }

    @Test
    @DisplayName("The navigation offers the queue to reviewers and hides it from employees")
    void navigationFollowsTheRole() throws Exception {
        String reviewerPage = mockMvc.perform(get("/").with(signedInAs(MANAGER_EMAIL)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String employeePage = mockMvc.perform(get("/").with(signedInAs(EMPLOYEE_EMAIL)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(reviewerPage).contains("/requests/review").contains("MANAGER");
        assertThat(employeePage).doesNotContain("/requests/review").contains("EMPLOYEE");
    }

    @Test
    @DisplayName("The sign-out control is a POST form carrying a CSRF token, not a link")
    void signOutIsAFormPost() throws Exception {
        String page = mockMvc.perform(get("/").with(signedInAs(EMPLOYEE_EMAIL)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(page).contains("action=\"/logout\"").contains("method=\"post\"").contains("_csrf");
    }

    @Test
    @DisplayName("A reviewer is offered the decision controls, the applicant is not")
    void decisionControlsAreOfferedToReviewersOnly() throws Exception {
        Long id = createAccessRequest(idOf(EMPLOYEE_EMAIL), "AWS", "needs a decision");

        String reviewerPage = mockMvc.perform(get("/requests/" + id).with(signedInAs(MANAGER_EMAIL)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String applicantPage = mockMvc.perform(get("/requests/" + id).with(signedInAs(EMPLOYEE_EMAIL)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(reviewerPage).contains("/approve").contains("/reject");
        assertThat(applicantPage).doesNotContain("/approve").doesNotContain("/reject");
    }

    @Test
    @DisplayName("An applicant who is also a reviewer is offered no decision controls on their own request")
    void applicantReviewerSeesNoControlsOnOwnRequest() throws Exception {
        Long managerId = idOf(MANAGER_EMAIL);
        Long id = createAccessRequest(managerId, "AWS", "I would like this myself");

        mockMvc.perform(get("/requests/" + id).with(signedInAs(MANAGER_EMAIL)))
                .andExpect(status().isOk())
                .andExpect(model().attribute("canReview", false))
                .andExpect(model().attribute("isApplicant", true))
                .andExpect(content().string(Matchers.not(Matchers.containsString("/approve"))));
    }

    @Test
    @DisplayName("A decided request offers no decision controls, even to a reviewer")
    void decidedRequestOffersNoControls() throws Exception {
        Long id = createAccessRequest(idOf(EMPLOYEE_EMAIL), "AWS", "already handled");
        approveEveryStage(id);

        mockMvc.perform(get("/requests/" + id).with(signedInAs(IT_ADMIN_EMAIL)))
                .andExpect(status().isOk())
                .andExpect(model().attribute("canReview", false))
                .andExpect(content().string(Matchers.containsString("APPROVED")))
                .andExpect(content().string(Matchers.containsString("ok")));
    }

    @Test
    @DisplayName("A user whose details changed is shown as the principal reports them")
    void identityComesFromThePrincipal() throws Exception {
        createUser("EMP-LIST-3", "listing.identity@accessflow.local", User.Role.IT_ADMIN);

        mockMvc.perform(get("/").with(signedInAs("listing.identity@accessflow.local")))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("Anita Sharma")))
                .andExpect(content().string(Matchers.containsString("listing.identity@accessflow.local")))
                .andExpect(content().string(Matchers.containsString("Engineering")));
    }
}
