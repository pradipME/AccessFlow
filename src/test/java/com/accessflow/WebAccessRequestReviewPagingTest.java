package com.accessflow;

import com.accessflow.dto.PagedResponse;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * The paginated review queue in the browser.
 *
 * The page holds the same envelope the API returns rather than a second, plainer
 * shape, so there is one definition of a page and the pages cannot drift from the
 * endpoint.
 */
@DisplayName("Phase 4 - the paginated review queue page")
@EnabledIfEnvironmentVariable(named = "ACCESSFLOW_DB_PASSWORD", matches = ".+")
class WebAccessRequestReviewPagingTest extends IntegrationTestSupport {

    @Test
    @DisplayName("An empty queue says so and offers no table")
    void emptyQueue() throws Exception {
        mockMvc.perform(get("/requests/review").with(signedInAs(MANAGER_EMAIL)))
                .andExpect(status().isOk())
                .andExpect(view().name("requests/review"))
                .andExpect(content().string(Matchers.containsString("No requests match this filter.")))
                .andExpect(content().string(Matchers.not(Matchers.containsString("<table"))))
                // One page needs no navigation.
                .andExpect(content().string(Matchers.not(Matchers.containsString("class=\"pagination\""))));
    }

    @Test
    @DisplayName("The page model holds the envelope, not a bare list")
    void modelHoldsTheEnvelope() throws Exception {
        createAccessRequest(idOf(EMPLOYEE_EMAIL), "GitHub", "one request");

        MvcResult result = mockMvc.perform(get("/requests/review").with(signedInAs(MANAGER_EMAIL)))
                .andExpect(status().isOk())
                .andReturn();

        PagedResponse<?> page = (PagedResponse<?>) result.getModelAndView()
                .getModel().get("page");

        assertThat(page).isNotNull();
        assertThat(page.items()).hasSize(1);
        assertThat(page.page()).isZero();
        assertThat(page.size()).isEqualTo(20);
        assertThat(page.totalElements()).isEqualTo(1);
    }

    @Test
    @DisplayName("The first page holds twenty requests and offers a way to the next")
    void firstPageOffersNext() throws Exception {
        Long employeeId = idOf(EMPLOYEE_EMAIL);
        for (int i = 0; i < 25; i++) {
            createAccessRequest(employeeId, "App" + i, "filler " + i);
        }

        mockMvc.perform(get("/requests/review").with(signedInAs(MANAGER_EMAIL)))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("Page 1 of 2")))
                .andExpect(content().string(Matchers.containsString("Next")))
                .andExpect(content().string(Matchers.containsString("page=1")))
                // No Previous on the first page: there is no page 0 to go back to.
                .andExpect(content().string(Matchers.not(Matchers.containsString("Previous"))))
                .andExpect(content().string(Matchers.containsString("25 request(s) total")));
    }

    @Test
    @DisplayName("The last page offers a way back and no way forward")
    void lastPageOffersPrevious() throws Exception {
        Long employeeId = idOf(EMPLOYEE_EMAIL);
        for (int i = 0; i < 25; i++) {
            createAccessRequest(employeeId, "App" + i, "filler " + i);
        }

        mockMvc.perform(get("/requests/review?page=1").with(signedInAs(MANAGER_EMAIL)))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("Page 2 of 2")))
                .andExpect(content().string(Matchers.containsString("Previous")))
                .andExpect(content().string(Matchers.not(Matchers.containsString(">Next<"))));
    }

    @Test
    @DisplayName("Consecutive pages show different requests")
    void consecutivePagesDiffer() throws Exception {
        Long employeeId = idOf(EMPLOYEE_EMAIL);
        for (int i = 0; i < 25; i++) {
            createAccessRequest(employeeId, "App" + i, "filler " + i);
        }

        String firstPage = reviewBody(0);
        String secondPage = reviewBody(1);

        assertThat(firstPage).isNotEqualTo(secondPage);
    }

    @Test
    @DisplayName("A page size can be chosen and is carried from page to page")
    void pageSizeIsCarriedBetweenPages() throws Exception {
        Long employeeId = idOf(EMPLOYEE_EMAIL);
        for (int i = 0; i < 25; i++) {
            createAccessRequest(employeeId, "App" + i, "filler " + i);
        }

        mockMvc.perform(get("/requests/review?size=10").with(signedInAs(MANAGER_EMAIL)))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("Page 1 of 3")))
                // The Next link keeps the size, so paging cannot silently change
                // the density the reviewer picked.
                .andExpect(content().string(Matchers.containsString("size=10")));
    }

    @Test
    @DisplayName("The status filter is kept in the paging links")
    void filterIsKeptInThePagingLinks() throws Exception {
        Long employeeId = idOf(EMPLOYEE_EMAIL);
        for (int i = 0; i < 25; i++) {
            createAccessRequest(employeeId, "App" + i, "filler " + i);
        }

        mockMvc.perform(get("/requests/review?status=PENDING&size=10")
                        .with(signedInAs(MANAGER_EMAIL)))
                .andExpect(status().isOk())
                // Dropping the filter would move the reviewer into a different
                // result set mid-browse, which is not what they asked for.
                .andExpect(content().string(Matchers.containsString("status=PENDING")));
    }

    @Test
    @DisplayName("The filter form does not carry the current page, so a new filter starts at the first")
    void filterFormResetsThePage() throws Exception {
        Long employeeId = idOf(EMPLOYEE_EMAIL);
        for (int i = 0; i < 25; i++) {
            createAccessRequest(employeeId, "App" + i, "filler " + i);
        }

        mockMvc.perform(get("/requests/review?page=2").with(signedInAs(MANAGER_EMAIL)))
                .andExpect(status().isOk())
                .andExpect(content().string(
                        Matchers.not(Matchers.containsString("<input type=\"hidden\" name=\"page\""))));
    }

    @Test
    @DisplayName("WITHDRAWN appears in the filter, because the workflow has that status")
    void withdrawnIsInTheFilter() throws Exception {
        mockMvc.perform(get("/requests/review").with(signedInAs(MANAGER_EMAIL)))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("WITHDRAWN")));
    }

    @Test
    @DisplayName("Filtering on WITHDRAWN shows the withdrawn requests")
    void withdrawnFilter() throws Exception {
        Long employeeId = idOf(EMPLOYEE_EMAIL);
        createAccessRequest(employeeId, "StillOpen", "waiting");
        Long withdrawn = createAccessRequest(employeeId, "TakenBack", "changed my mind");
        accessRequestService.withdrawRequest(withdrawn, employeeId, "no longer needed");

        mockMvc.perform(get("/requests/review?status=WITHDRAWN").with(signedInAs(MANAGER_EMAIL)))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("TakenBack")))
                .andExpect(content().string(Matchers.not(Matchers.containsString("StillOpen"))))
                .andExpect(content().string(Matchers.containsString("1 request(s) total")));
    }

    @Test
    @DisplayName("A withdrawn request in the queue carries the withdrawn badge")
    void withdrawnBadgeInTheQueue() throws Exception {
        Long employeeId = idOf(EMPLOYEE_EMAIL);
        Long withdrawn = createAccessRequest(employeeId, "TakenBack", "changed my mind");
        accessRequestService.withdrawRequest(withdrawn, employeeId, "no longer needed");

        mockMvc.perform(get("/requests/review").with(signedInAs(MANAGER_EMAIL)))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("badge withdrawn")));
    }

    @Test
    @DisplayName("An employee cannot reach the review queue")
    void employeeCannotReachTheQueue() throws Exception {
        createAccessRequest(idOf(EMPLOYEE_EMAIL), "AWS", "not for you");

        mockMvc.perform(get("/requests/review").with(signedInAs(EMPLOYEE_EMAIL)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("A signed-out visitor cannot reach the review queue")
    void signedOutCannotReachTheQueue() throws Exception {
        mockMvc.perform(get("/requests/review"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    @DisplayName("A negative page is a 400 page rather than a silently corrected one")
    void negativePageIsABadRequestPage() throws Exception {
        mockMvc.perform(get("/requests/review?page=-1").with(signedInAs(MANAGER_EMAIL)))
                .andExpect(status().isBadRequest())
                .andExpect(view().name("error"));
    }

    @Test
    @DisplayName("A size of zero is a 400 page")
    void sizeOfZeroIsABadRequestPage() throws Exception {
        mockMvc.perform(get("/requests/review?size=0").with(signedInAs(MANAGER_EMAIL)))
                .andExpect(status().isBadRequest())
                .andExpect(view().name("error"));
    }

    @Test
    @DisplayName("A size over the maximum is a 400 page, not a clamped one")
    void sizeOverTheMaximumIsABadRequestPage() throws Exception {
        mockMvc.perform(get("/requests/review?size=101").with(signedInAs(MANAGER_EMAIL)))
                .andExpect(status().isBadRequest())
                .andExpect(view().name("error"));
    }

    @Test
    @DisplayName("A non-numeric page is a 400 page")
    void nonNumericPageIsABadRequestPage() throws Exception {
        mockMvc.perform(get("/requests/review?page=many").with(signedInAs(MANAGER_EMAIL)))
                .andExpect(status().isBadRequest())
                .andExpect(view().name("error"));
    }

    @Test
    @DisplayName("An unknown status filter is a 400 page")
    void unknownStatusIsABadRequestPage() throws Exception {
        mockMvc.perform(get("/requests/review?status=BANANA").with(signedInAs(MANAGER_EMAIL)))
                .andExpect(status().isBadRequest())
                .andExpect(view().name("error"));
    }

    @Test
    @DisplayName("A page past the end renders empty with a way back")
    void pagePastTheEndRendersEmpty() throws Exception {
        Long employeeId = idOf(EMPLOYEE_EMAIL);
        for (int i = 0; i < 3; i++) {
            createAccessRequest(employeeId, "App" + i, "filler " + i);
        }

        // A stale bookmark should not be a 404. Previous is offered so the
        // reviewer has a way out, and the total is still reported.
        mockMvc.perform(get("/requests/review?page=99").with(signedInAs(MANAGER_EMAIL)))
                .andExpect(status().isOk())
                .andExpect(view().name("requests/review"))
                .andExpect(content().string(Matchers.containsString("No requests match this filter.")))
                .andExpect(content().string(Matchers.containsString("Previous")))
                .andExpect(content().string(Matchers.containsString("3 request(s) total")));
    }

    @Test
    @DisplayName("The queue is newest first, matching every other list")
    void queueIsNewestFirst() throws Exception {
        Long employeeId = idOf(EMPLOYEE_EMAIL);
        createAccessRequest(employeeId, "Oldest", "first");
        createAccessRequest(employeeId, "Newest", "second");

        String page = reviewBody(0);

        assertThat(page.indexOf("Newest")).isLessThan(page.indexOf("Oldest"));
    }

    /**
     * Fetches one review page and returns the rendered HTML, for the assertions
     * that compare two pages or compare positions within one.
     */
    private String reviewBody(int pageNumber) throws Exception {
        return mockMvc.perform(get("/requests/review?page=" + pageNumber)
                        .with(signedInAs(MANAGER_EMAIL)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }
}
