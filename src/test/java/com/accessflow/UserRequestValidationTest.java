package com.accessflow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("Step 9 - request validation")
@EnabledIfEnvironmentVariable(named = "ACCESSFLOW_DB_PASSWORD", matches = ".+")
class UserRequestValidationTest extends IntegrationTestSupport {

    private static final String OK_EMAIL = "blank.check@accessflow.local";

    @Test
    @DisplayName("A valid registration is accepted")
    void validRequestAccepted() throws Exception {
        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRegistrationJson()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.employeeId").value("EMP-7001"));
    }

    @Test
    @DisplayName("Missing required fields are rejected with 400 and one error per field")
    void missingRequiredFieldsRejected() throws Exception {
        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registrationJson(null, null, null, null, null, null, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.validationErrors").isArray())
                .andExpect(jsonPath("$.validationErrors.length()").value(6));
    }

    @Test
    @DisplayName("Blank employeeId, firstName and lastName are rejected")
    void blankRequiredTextRejected() throws Exception {
        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registrationJson("", "", "", OK_EMAIL, PASSWORD, "EMPLOYEE", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors[?(@.field == 'employeeId')]").exists())
                .andExpect(jsonPath("$.validationErrors[?(@.field == 'firstName')]").exists())
                .andExpect(jsonPath("$.validationErrors[?(@.field == 'lastName')]").exists());
    }

    @Test
    @DisplayName("A malformed email is rejected")
    void invalidEmailRejected() throws Exception {
        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registrationJson("EMP-7101", "A", "B", "not-an-email", PASSWORD,
                                "EMPLOYEE", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors[?(@.field == 'email')]").exists());
    }

    @Test
    @DisplayName("A password shorter than the minimum is rejected")
    void shortPasswordRejected() throws Exception {
        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registrationJson("EMP-7102", "A", "B", "short.pw@accessflow.local",
                                "abc", "EMPLOYEE", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors[?(@.field == 'password')]").exists());
    }

    @Test
    @DisplayName("An unknown role value is rejected")
    void unknownRoleRejected() throws Exception {
        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registrationJson("EMP-7103", "A", "B", "role.check@accessflow.local",
                                PASSWORD, "GOD", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    @DisplayName("Over-long values are rejected at the configured maximums")
    void overLongValuesRejected() throws Exception {
        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registrationJson("E".repeat(51), "F".repeat(101), "G".repeat(101),
                                "h".repeat(250) + "@accessflow.local", PASSWORD, "EMPLOYEE",
                                "D".repeat(101))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(5)));
    }

    @Test
    @DisplayName("Department is optional but length-limited when supplied")
    void departmentOptionalButLengthLimited() throws Exception {
        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registrationJson("EMP-7104", "A", "B", "nodept@accessflow.local",
                                PASSWORD, "EMPLOYEE", null)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registrationJson("EMP-7105", "A", "B", "longdept@accessflow.local",
                                PASSWORD, "EMPLOYEE", "D".repeat(101))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors[?(@.field == 'department')]").exists());
    }

    @Test
    @DisplayName("A malformed JSON body is rejected with 400")
    void malformedBodyRejected() throws Exception {
        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Malformed or unreadable request body"));
    }

    @Test
    @DisplayName("Validation messages never echo the submitted password")
    void validationErrorsDoNotLeakPassword() throws Exception {
        String secret = "SuperSecretValue123";
        String body = mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registrationJson("EMP-7106", "A", "B", "leak@accessflow.local",
                                secret, "NOT_A_ROLE", null)))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(body).doesNotContain(secret);
    }
}
