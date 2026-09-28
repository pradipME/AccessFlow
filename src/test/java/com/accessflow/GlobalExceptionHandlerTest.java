package com.accessflow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("Step 10 - GlobalExceptionHandler")
@EnabledIfEnvironmentVariable(named = "ACCESSFLOW_DB_PASSWORD", matches = ".+")
class GlobalExceptionHandlerTest extends IntegrationTestSupport {

    private static final String SUBJECT_EMAIL = "err.9001@accessflow.local";

    @Test
    @DisplayName("UserNotFoundException maps to 404 with a populated ErrorResponse")
    void notFoundMapsTo404() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/users/employee/EMP-NOPE")
                        .with(httpBasic(EMPLOYEE_EMAIL, PASSWORD)))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("\"timestamp\"")
                .contains("\"status\":404")
                .contains("\"error\":\"Not Found\"")
                .contains("\"message\"")
                .contains("\"path\":\"/api/users/employee/EMP-NOPE\"")
                .contains("\"validationErrors\":[]");
    }

    @Test
    @DisplayName("DuplicateUserException maps to 409 and reports the field cleanly")
    void duplicateMapsTo409() throws Exception {
        createEmployee("EMP-9002", SUBJECT_EMAIL);

        MvcResult result = mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registrationJson("EMP-9002", "Dup", "Licate", "other.9001@accessflow.local",
                                PASSWORD, "EMPLOYEE", null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.validationErrors[0].field").value("employeeId"))
                .andExpect(jsonPath("$.validationErrors[0].message").value("already in use"))
                .andReturn();

        assertThat(result.getResponse().getContentAsString())
                .contains("\"status\":409")
                .contains("\"error\":\"Conflict\"");
    }

    @Test
    @DisplayName("MethodArgumentNotValidException maps to 400 listing every failed field")
    void validationMapsTo400() throws Exception {
        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registrationJson(null, null, null, null, null, null, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.validationErrors").isNotEmpty());
    }

    @Test
    @DisplayName("Malformed request body maps to 400")
    void malformedMapsTo400() throws Exception {
        mockMvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON).content("{["))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Malformed or unreadable request body"));
    }

    @Test
    @DisplayName("Invalid credentials map to 401")
    void invalidCredentialsMapsTo401() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + EMPLOYEE_EMAIL + "\",\"password\":\"not-the-password\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").value("Invalid email or password"));
    }

    @Test
    @DisplayName("No error body leaks a stack trace or internal class name")
    void errorBodiesCarryNoStackTrace() throws Exception {
        String notFound = mockMvc.perform(get("/api/users/employee/EMP-NOPE")
                        .with(httpBasic(EMPLOYEE_EMAIL, PASSWORD)))
                .andReturn().getResponse().getContentAsString();

        String malformed = mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON).content("{["))
                .andReturn().getResponse().getContentAsString();

        for (String body : new String[]{notFound, malformed}) {
            assertThat(body).doesNotContain("trace");
            assertThat(body).doesNotContain("Exception");
            assertThat(body).doesNotContain("org.springframework");
            assertThat(body).doesNotContain("com.accessflow");
            assertThat(body).doesNotContain("\tat ");
        }
    }
}
