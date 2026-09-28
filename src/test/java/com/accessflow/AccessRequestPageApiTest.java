package com.accessflow;

import com.accessflow.entity.User;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 4 - the paginated review queue.
 *
 * The tests that matter here are the ones about the envelope and the order. Spring
 * would happily serialise a {@code Page} and a {@code Sort} the client chose, and
 * both would work; they are refused here on purpose, so these assertions are what
 * stops a refactor from quietly restoring either.
 */
@DisplayName("Phase 4 - paginated review queue API")
@EnabledIfEnvironmentVariable(named = "ACCESSFLOW_DB_PASSWORD", matches = ".+")
class AccessRequestPageApiTest extends IntegrationTestSupport {

    @Test
    @DisplayName("An empty queue is a 200 with an empty, self-consistent envelope")
    void emptyQueue() throws Exception {
        mockMvc.perform(get("/api/access-requests/page").with(httpBasic(MANAGER_EMAIL, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.totalPages").value(0))
                .andExpect(jsonPath("$.first").value(true))
                .andExpect(jsonPath("$.last").value(true));
    }

    @Test
    @DisplayName("Every reviewer role may page the queue")
    void everyReviewerRoleMayPage() throws Exception {
        createAccessRequest(idOf(EMPLOYEE_EMAIL), "GitHub", "queue me");

        for (String email : new String[]{MANAGER_EMAIL, IT_ADMIN_EMAIL, SUPER_ADMIN_EMAIL}) {
            mockMvc.perform(get("/api/access-requests/page").with(httpBasic(email, PASSWORD)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items").isNotEmpty());
        }
    }

    @Test
    @DisplayName("An employee is forbidden from the paginated queue")
    void employeeCannotPage() throws Exception {
        createAccessRequest(idOf(EMPLOYEE_EMAIL), "GitHub", "not for you");

        mockMvc.perform(get("/api/access-requests/page").with(httpBasic(EMPLOYEE_EMAIL, PASSWORD)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    @DisplayName("Paging without authentication is refused")
    void pagingRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/access-requests/page"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("The default page holds twenty items")
    void defaultSizeIsTwenty() throws Exception {
        Long employeeId = idOf(EMPLOYEE_EMAIL);
        for (int i = 0; i < 25; i++) {
            createAccessRequest(employeeId, "App" + i, "filler " + i);
        }

        mockMvc.perform(get("/api/access-requests/page").with(httpBasic(MANAGER_EMAIL, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", Matchers.hasSize(20)))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(25))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.first").value(true))
                .andExpect(jsonPath("$.last").value(false));
    }

    @Test
    @DisplayName("Consecutive pages do not repeat or skip a request")
    void pagesPartitionTheQueue() throws Exception {
        Long employeeId = idOf(EMPLOYEE_EMAIL);
        for (int i = 0; i < 25; i++) {
            createAccessRequest(employeeId, "App" + i, "filler " + i);
        }

        String firstPage = page(0, 10);
        String secondPage = page(1, 10);
        String thirdPage = page(2, 10);

        // 25 requests over pages of 10: the third holds 5 and the other two 10.
        assertThat(firstPage).isNotEqualTo(secondPage).isNotEqualTo(thirdPage);
        assertThat(secondPage).isNotEqualTo(thirdPage);
    }

    @Test
    @DisplayName("The queue is newest first")
    void newestFirst() throws Exception {
        Long employeeId = idOf(EMPLOYEE_EMAIL);
        createAccessRequest(employeeId, "Oldest", "first");
        Long middle = createAccessRequest(employeeId, "Middle", "second");
        createAccessRequest(employeeId, "Newest", "third");

        mockMvc.perform(get("/api/access-requests/page").with(httpBasic(MANAGER_EMAIL, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", Matchers.hasSize(3)))
                .andExpect(jsonPath("$.items[0].application").value("Newest"))
                .andExpect(jsonPath("$.items[1].application").value("Middle"))
                .andExpect(jsonPath("$.items[2].application").value("Oldest"));
        assertThat(middle).isNotNull();
    }

    @Test
    @DisplayName("The id breaks a tie on the timestamp, so the order is total and stable")
    void tieOnTimestampIsBrokenById() throws Exception {
        // createdAt comes from the database and is not unique. Without the id
        // tie-break two rows sharing a value could come back in either order
        // between calls, and a paginated query would then repeat or skip them.
        // Identifiers increase, so the newest request is the highest id.
        Long employeeId = idOf(EMPLOYEE_EMAIL);
        for (int i = 0; i < 4; i++) {
            createAccessRequest(employeeId, "App" + i, "filler " + i);
        }

        String firstRead = page(0, 10);
        String secondRead = page(0, 10);

        assertThat(firstRead).isEqualTo(secondRead);
        assertThat(firstRead).contains("\"application\":\"App3\"", "\"application\":\"App2\"");
    }

    @Test
    @DisplayName("A status filter narrows the page and its count")
    void statusFilterNarrowsThePage() throws Exception {
        Long employeeId = idOf(EMPLOYEE_EMAIL);
        for (int i = 0; i < 3; i++) {
            createAccessRequest(employeeId, "Pending" + i, "still waiting " + i);
        }
        Long approved = createAccessRequest(employeeId, "Approved", "granted");
        approveEveryStage(approved);
        Long withdrawn = createAccessRequest(employeeId, "Withdrawn", "changed my mind");
        accessRequestService.withdrawRequest(withdrawn, employeeId, "no longer needed");

        mockMvc.perform(get("/api/access-requests/page?status=APPROVED")
                        .with(httpBasic(MANAGER_EMAIL, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].status").value("APPROVED"));
    }

    @Test
    @DisplayName("WITHDRAWN is a status a reviewer can filter on")
    void withdrawnIsFilterable() throws Exception {
        Long employeeId = idOf(EMPLOYEE_EMAIL);
        createAccessRequest(employeeId, "StillPending", "waiting");
        Long withdrawn = createAccessRequest(employeeId, "Withdrawn", "changed my mind");
        accessRequestService.withdrawRequest(withdrawn, employeeId, "no longer needed");

        mockMvc.perform(get("/api/access-requests/page?status=WITHDRAWN")
                        .with(httpBasic(MANAGER_EMAIL, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].status").value("WITHDRAWN"))
                .andExpect(jsonPath("$.items[0].withdrawnAt").exists());
    }

    @Test
    @DisplayName("An unknown status filter is a 400, not a 500")
    void unknownStatusFilterIsBadRequest() throws Exception {
        mockMvc.perform(get("/api/access-requests/page?status=BANANA")
                        .with(httpBasic(MANAGER_EMAIL, PASSWORD)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    @DisplayName("A page past the end is an empty 200, not an error")
    void pagePastTheEndIsEmpty() throws Exception {
        Long employeeId = idOf(EMPLOYEE_EMAIL);
        for (int i = 0; i < 3; i++) {
            createAccessRequest(employeeId, "App" + i, "filler " + i);
        }

        // A stale bookmark should degrade to an empty list, not 404. A reviewer
        // who was on page 5 when the queue shrank has a way back through the
        // previous-page link, which is exactly why first/last are in the envelope.
        mockMvc.perform(get("/api/access-requests/page?page=99")
                        .with(httpBasic(MANAGER_EMAIL, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.page").value(99))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.first").value(false))
                .andExpect(jsonPath("$.last").value(true));
    }

    @Test
    @DisplayName("A negative page is a 400 naming the parameter")
    void negativePageIsBadRequest() throws Exception {
        mockMvc.perform(get("/api/access-requests/page?page=-1")
                        .with(httpBasic(MANAGER_EMAIL, PASSWORD)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.validationErrors[?(@.field == 'page')]").exists());
    }

    @Test
    @DisplayName("A size below one is a 400 naming the parameter")
    void sizeBelowOneIsBadRequest() throws Exception {
        mockMvc.perform(get("/api/access-requests/page?size=0")
                        .with(httpBasic(MANAGER_EMAIL, PASSWORD)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.validationErrors[?(@.field == 'size')]").exists());
    }

    @Test
    @DisplayName("A size over the maximum is a 400, not silently clamped")
    void sizeOverMaximumIsBadRequest() throws Exception {
        // Clamping a size=1000 to 100 would answer a question nobody asked and
        // hide the truncation; the caller is told instead.
        mockMvc.perform(get("/api/access-requests/page?size=101")
                        .with(httpBasic(MANAGER_EMAIL, PASSWORD)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.validationErrors[?(@.field == 'size')]").exists());
    }

    @Test
    @DisplayName("A negative size is a 400")
    void negativeSizeIsBadRequest() throws Exception {
        mockMvc.perform(get("/api/access-requests/page?size=-5")
                        .with(httpBasic(MANAGER_EMAIL, PASSWORD)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("The maximum size is accepted, and the boundary is exact")
    void maximumSizeIsAccepted() throws Exception {
        mockMvc.perform(get("/api/access-requests/page?size=100")
                        .with(httpBasic(MANAGER_EMAIL, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(100));
    }

    @Test
    @DisplayName("A client-chosen sort is refused, because the order is fixed")
    void clientChosenSortIsRefused() throws Exception {
        // Spring's Pageable would honour this and order by the applicant's name.
        // Paging is only correct while the order is the one the service defines:
        // without the id tie-break a row can be returned on two pages or on none.
        mockMvc.perform(get("/api/access-requests/page?sort=application,asc")
                        .with(httpBasic(MANAGER_EMAIL, PASSWORD)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.validationErrors[?(@.field == 'sort')]").exists());
    }

    @Test
    @DisplayName("An empty sort parameter is accepted, being the same as none")
    void emptySortIsAccepted() throws Exception {
        mockMvc.perform(get("/api/access-requests/page?sort=")
                        .with(httpBasic(MANAGER_EMAIL, PASSWORD)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("A non-numeric page is a 400, not a 500")
    void nonNumericPageIsBadRequest() throws Exception {
        mockMvc.perform(get("/api/access-requests/page?page=many")
                        .with(httpBasic(MANAGER_EMAIL, PASSWORD)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("A bad paging value is the same uniform error body as any other client error")
    void pagingErrorsUseTheStandardBody() throws Exception {
        mockMvc.perform(get("/api/access-requests/page?page=-1")
                        .with(httpBasic(MANAGER_EMAIL, PASSWORD)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.path").value("/api/access-requests/page"))
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    @DisplayName("The queue page is not Spring's Page serialisation")
    void envelopeIsOurs() throws Exception {
        // Spring's Page adds fields like pageable, sort, numberOfElements and
        // unpaged. Their presence would tie the API to a framework version, so
        // the envelope is this project's own and these must be absent.
        String body = mockMvc.perform(get("/api/access-requests/page")
                        .with(httpBasic(MANAGER_EMAIL, PASSWORD)))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("pageable")
                .doesNotContain("numberOfElements")
                .doesNotContain("unpaged")
                .doesNotContain("\"sort\"");
    }

    @Test
    @DisplayName("The existing list endpoint is untouched and still answers with a bare array")
    void existingListEndpointIsUnchanged() throws Exception {
        // Phase 4 added /page rather than changing this one, so a client written
        // against the earlier phases still gets what it always got.
        Long employeeId = idOf(EMPLOYEE_EMAIL);
        for (int i = 0; i < 25; i++) {
            createAccessRequest(employeeId, "App" + i, "filler " + i);
        }

        String body = mockMvc.perform(get("/api/access-requests")
                        .with(httpBasic(MANAGER_EMAIL, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // An array of 25, not an envelope, and not truncated to the default page
        // size of 20. Counted on "application", which appears once per element:
        // "id" would also match the applicant and reviewer ids.
        assertThat(body).startsWith("[").contains("\"application\"").doesNotContain("totalElements");
        assertThat(body.split("\"application\"", -1)).hasSize(26);
    }

    @Test
    @DisplayName("Paged items carry the same fields as the unwrapped response")
    void pagedItemsMatchTheUnpagedShape() throws Exception {
        Long id = createAccessRequest(idOf(EMPLOYEE_EMAIL), "AWS", "shape check");
        accessRequestService.withdrawRequest(id, idOf(EMPLOYEE_EMAIL), "changed my mind");

        mockMvc.perform(get("/api/access-requests/page")
                        .with(httpBasic(MANAGER_EMAIL, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(id))
                .andExpect(jsonPath("$.items[0].application").value("AWS"))
                .andExpect(jsonPath("$.items[0].status").value("WITHDRAWN"))
                .andExpect(jsonPath("$.items[0].withdrawnAt").exists())
                .andExpect(jsonPath("$.items[0].withdrawalReason").value("changed my mind"))
                .andExpect(jsonPath("$.items[0].applicant.email").value(EMPLOYEE_EMAIL))
                .andExpect(jsonPath("$.items[0].createdAt").exists());
    }

    @Test
    @DisplayName("No paged response contains a password or hash")
    void noPasswordIsEverReturned() throws Exception {
        Long employeeId = idOf(EMPLOYEE_EMAIL);
        for (int i = 0; i < 3; i++) {
            createAccessRequest(employeeId, "App" + i, "checking for leaks " + i);
        }

        String body = mockMvc.perform(get("/api/access-requests/page")
                        .with(httpBasic(MANAGER_EMAIL, PASSWORD)))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("password")
                .doesNotContain(PASSWORD)
                .doesNotContain("$2a$")
                .doesNotContain("$2b$");
    }

    @Test
    @DisplayName("A withdrawn request stays in the queue rather than disappearing from it")
    void withdrawnRequestsRemainVisibleToReviewers() throws Exception {
        // A withdrawal is not a deletion. A reviewer still needs to see that
        // somebody withdrew and when, which is the same reason the history is
        // there.
        Long employeeId = idOf(EMPLOYEE_EMAIL);
        createAccessRequest(employeeId, "StillOpen", "waiting");
        Long withdrawn = createAccessRequest(employeeId, "TakenBack", "changed my mind");
        accessRequestService.withdrawRequest(withdrawn, employeeId, "no longer needed");

        mockMvc.perform(get("/api/access-requests/page")
                        .with(httpBasic(MANAGER_EMAIL, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].status",
                        hasItems("PENDING", "WITHDRAWN")));
    }

    @Test
    @DisplayName("Filtering and paging combine, and the count reflects both")
    void filterAndPagingCombine() throws Exception {
        Long employeeId = idOf(EMPLOYEE_EMAIL);
        for (int i = 0; i < 5; i++) {
            Long id = createAccessRequest(employeeId, "Pending" + i, "still waiting " + i);
            if (i % 2 == 0) {
                approveEveryStage(id);
            }
        }
        // Two stay pending from the loop, plus this one: three in total, of which
        // one fits on a page of size 1.
        createAccessRequest(employeeId, "Withdrawn", "changed my mind");

        mockMvc.perform(get("/api/access-requests/page?status=PENDING&page=0&size=1")
                        .with(httpBasic(MANAGER_EMAIL, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].status").value("PENDING"))
                // The count is the filtered one, not the whole table: six
        // requests exist, three of them pending.
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.first").value(true))
                .andExpect(jsonPath("$.last").value(false));

        // The last of the three pages holds the same single item, and is last.
        mockMvc.perform(get("/api/access-requests/page?status=PENDING&page=2&size=1")
                        .with(httpBasic(MANAGER_EMAIL, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.last").value(true));
    }

    /**
     * Fetches one page of the queue and returns the body, for the assertions that
     * compare two reads or count occurrences in a string.
     */
    private String page(int page, int size) throws Exception {
        return mockMvc.perform(get("/api/access-requests/page?page=" + page + "&size=" + size)
                        .with(httpBasic(MANAGER_EMAIL, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }
}
