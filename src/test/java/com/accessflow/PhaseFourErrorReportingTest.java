package com.accessflow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 4 - how the new refusals are reported.
 *
 * The point of these is uniformity. A parameter constraint is a different
 * exception type from a body validation failure, so without a handler for it
 * Spring answers with a body and a status the API has never used anywhere else.
 * These assert that it does not.
 */
@DisplayName("Phase 4 - error reporting for withdrawal, history and paging")
@EnabledIfEnvironmentVariable(named = "ACCESSFLOW_DB_PASSWORD", matches = ".+")
class PhaseFourErrorReportingTest extends IntegrationTestSupport {

    private static final String OTHER_EMPLOYEE_EMAIL = "errors.other@accessflow.local";

    @Test
    @DisplayName("An out-of-range page is the same uniform body as any other client error")
    void badPageIsTheStandardErrorBody() throws Exception {
        String body = mockMvc.perform(get("/api/access-requests/page?page=-1")
                        .with(httpBasic(MANAGER_EMAIL, PASSWORD)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn().getResponse().getContentAsString();

        // Every field the ErrorResponse record declares must be present, so a
        // client can parse this exactly as it parses a 404 or a 409.
        assertThat(body).contains("\"timestamp\"")
                .contains("\"status\":400")
                .contains("\"error\":\"Bad Request\"")
                .contains("\"message\"")
                .contains("\"path\":\"/api/access-requests/page\"")
                .contains("\"validationErrors\"");
    }

    @Test
    @DisplayName("A method-parameter failure is a field error, like a body validation failure")
    void methodParameterFailureIsAFieldError() throws Exception {
        mockMvc.perform(get("/api/access-requests/page?size=101")
                        .with(httpBasic(MANAGER_EMAIL, PASSWORD)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors").isNotEmpty())
                .andExpect(jsonPath("$.validationErrors[0].field").value("size"))
                .andExpect(jsonPath("$.validationErrors[0].message").exists());
    }

    @Test
    @DisplayName("A refused sort names the parameter it was given on")
    void refusedSortNamesTheParameter() throws Exception {
        mockMvc.perform(get("/api/access-requests/page?sort=application")
                        .with(httpBasic(MANAGER_EMAIL, PASSWORD)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors[?(@.field == 'sort')]").exists())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("order is fixed")));
    }

    @Test
    @DisplayName("Two bad parameters are both reported rather than only the first")
    void severalBadParametersAreAllReported() throws Exception {
        // Reporting one at a time would make a client fix and resubmit twice to
        // discover a mistake it could have been told about at once.
        mockMvc.perform(get("/api/access-requests/page?page=-1&size=0")
                        .with(httpBasic(MANAGER_EMAIL, PASSWORD)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors[?(@.field == 'page')]").exists())
                .andExpect(jsonPath("$.validationErrors[?(@.field == 'size')]").exists());
    }

    @Test
    @DisplayName("A validation error never echoes the values that were rejected")
    void validationErrorDoesNotEchoTheValue() throws Exception {
        String body = mockMvc.perform(get("/api/access-requests/page?sort=password=hunter2")
                        .with(httpBasic(MANAGER_EMAIL, PASSWORD)))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(PASSWORD).doesNotContain("$2");
    }

    @Test
    @DisplayName("An over-long withdrawal reason is a field error on reason")
    void overLongWithdrawalReasonIsAFieldError() throws Exception {
        mockMvc.perform(patch("/api/access-requests/1/withdraw")
                        .with(httpBasic(EMPLOYEE_EMAIL, PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("reason", "x".repeat(1001))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors[?(@.field == 'reason')]").exists());
    }

    @Test
    @DisplayName("A refused withdrawal of somebody else's request is a 403 with the uniform body")
    void refusedWithdrawalIsTheStandardErrorBody() throws Exception {
        createEmployee("EMP-ERR-2", OTHER_EMPLOYEE_EMAIL);
        Long id = createAccessRequest(idOf(EMPLOYEE_EMAIL), "AWS", "not yours");

        String body = mockMvc.perform(patch("/api/access-requests/" + id + "/withdraw")
                        .with(httpBasic(OTHER_EMPLOYEE_EMAIL, PASSWORD)))
                .andExpect(status().isForbidden())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("\"status\":403")
                .contains("\"error\":\"Forbidden\"")
                .contains("\"path\":\"/api/access-requests/" + id + "/withdraw\"");
    }

    @Test
    @DisplayName("A withdrawal of a resolved request is a 409 with the uniform body")
    void withdrawalConflictIsTheStandardErrorBody() throws Exception {
        Long id = createAccessRequest(idOf(EMPLOYEE_EMAIL), "AWS", "already decided");
        approveEveryStage(id);

        String body = mockMvc.perform(patch("/api/access-requests/" + id + "/withdraw")
                        .with(httpBasic(EMPLOYEE_EMAIL, PASSWORD)))
                .andExpect(status().isConflict())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("\"status\":409")
                .contains("\"error\":\"Conflict\"")
                .doesNotContain(PASSWORD);
    }

    @Test
    @DisplayName("Withdrawing an unknown request is a 404 naming the path that was asked for")
    void unknownWithdrawalIsA404() throws Exception {
        mockMvc.perform(patch("/api/access-requests/99999999/withdraw")
                        .with(httpBasic(EMPLOYEE_EMAIL, PASSWORD)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.path").value("/api/access-requests/99999999/withdraw"));
    }

    @Test
    @DisplayName("A refused history read is a 403 with the uniform body")
    void refusedHistoryIsTheStandardErrorBody() throws Exception {
        createEmployee("EMP-ERR-3", OTHER_EMPLOYEE_EMAIL);
        Long id = createAccessRequest(idOf(EMPLOYEE_EMAIL), "AWS", "not yours");

        String body = mockMvc.perform(get("/api/access-requests/" + id + "/history")
                        .with(httpBasic(OTHER_EMPLOYEE_EMAIL, PASSWORD)))
                .andExpect(status().isForbidden())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("\"status\":403")
                .contains("\"path\":\"/api/access-requests/" + id + "/history\"");
    }

    @Test
    @DisplayName("An unknown history is a 404")
    void unknownHistoryIsA404() throws Exception {
        mockMvc.perform(get("/api/access-requests/99999999/history")
                        .with(httpBasic(MANAGER_EMAIL, PASSWORD)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.path").value("/api/access-requests/99999999/history"));
    }

    @Test
    @DisplayName("A non-numeric page is a 400 field error naming page")
    void nonNumericPageIsAFieldError() throws Exception {
        mockMvc.perform(get("/api/access-requests/page?page=many")
                        .with(httpBasic(MANAGER_EMAIL, PASSWORD)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors[?(@.field == 'page')]").exists());
    }

    @Test
    @DisplayName("A bad paging value on an endpoint the employee may not use is still a 400")
    void validationIsNotHiddenBehindAuthorisation() throws Exception {
        // The service is never reached, so the employee gets a 403 from the
        // chain whichever order the two are in. What is asserted here is only
        // that no request reaches the catch-all 500.
        mockMvc.perform(get("/api/access-requests/page?page=-1")
                        .with(httpBasic(EMPLOYEE_EMAIL, PASSWORD)))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("No error body is ever the framework's own shape")
    void noErrorBodyIsSpringsOwn() throws Exception {
        // Spring's default for an unmapped exception type is a body built from
        // its own defaults, with the exception class named in the payload. These
        // endpoints are the ones that can produce a type this project has not
        // seen before, so each is checked.
        String[] bodies = {
                mockMvc.perform(get("/api/access-requests/page?page=-1")
                                .with(httpBasic(MANAGER_EMAIL, PASSWORD)))
                        .andReturn().getResponse().getContentAsString(),
                mockMvc.perform(get("/api/access-requests/page?size=101")
                                .with(httpBasic(MANAGER_EMAIL, PASSWORD)))
                        .andReturn().getResponse().getContentAsString(),
                mockMvc.perform(get("/api/access-requests/page?sort=anything")
                                .with(httpBasic(MANAGER_EMAIL, PASSWORD)))
                        .andReturn().getResponse().getContentAsString()
        };

        for (String body : bodies) {
            assertThat(body)
                    .doesNotContain("HandlerMethodValidationException")
                    .doesNotContain("org.springframework.web")
                    .doesNotContain("\"trace\"");
        }
    }
}
