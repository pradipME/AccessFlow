package com.accessflow;

import com.accessflow.entity.User;
import com.accessflow.repository.UserRepository;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end walk of the user-management API against the real application
 * configuration, covering the 17 scenarios required for Phase 1B.
 */
@DisplayName("Step 13 - end-to-end API")
@EnabledIfEnvironmentVariable(named = "ACCESSFLOW_DB_PASSWORD", matches = ".+")
class UserApiEndToEndTest extends IntegrationTestSupport {

    private static final String SUBJECT = "e2e.subject@accessflow.local";

    @Autowired
    private UserRepository userRepository;

    @Test
    @DisplayName("1. Registration succeeds and persists a BCrypt hash")
    void registration() throws Exception {
        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRegistrationJson()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.employeeId").value("EMP-7001"));

        assertThat(userRepository.findByEmail("anita.sharma@accessflow.local").orElseThrow()
                .getPassword()).startsWith("$2");
    }

    @Test
    @DisplayName("2. Duplicate employeeId returns 409")
    void duplicateEmployeeId() throws Exception {
        createEmployee("EMP-7001", SUBJECT);

        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registrationJson("EMP-7001", "A", "B", "other@accessflow.local",
                                PASSWORD, "EMPLOYEE", null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.validationErrors[0].field").value("employeeId"));
    }

    @Test
    @DisplayName("3. Duplicate email returns 409")
    void duplicateEmail() throws Exception {
        createEmployee("EMP-7001", SUBJECT);

        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registrationJson("EMP-7002", "A", "B", SUBJECT,
                                PASSWORD, "EMPLOYEE", null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.validationErrors[0].field").value("email"));
    }

    @Test
    @DisplayName("4. Invalid request returns 400 with field-level errors")
    void invalidRequest() throws Exception {
        // A valid role is deliberate: an unparseable enum is rejected by Jackson
        // before Bean Validation ever runs, which would assert the wrong layer.
        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registrationJson("", "A", "B", "bad-email", "x",
                                "EMPLOYEE", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors").isNotEmpty())
                .andExpect(jsonPath("$.validationErrors[?(@.field == 'employeeId')]").exists())
                .andExpect(jsonPath("$.validationErrors[?(@.field == 'email')]").exists())
                .andExpect(jsonPath("$.validationErrors[?(@.field == 'password')]").exists());
    }

    @Test
    @DisplayName("5. Login success returns 200 with the role")
    void loginSuccess() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + EMPLOYEE_EMAIL + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("EMPLOYEE"));
    }

    @Test
    @DisplayName("6. Login failure returns 401")
    void loginFailure() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + EMPLOYEE_EMAIL + "\",\"password\":\"nope\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("7. Unauthenticated protected request returns 401")
    void unauthenticatedRequest() throws Exception {
        Long id = createEmployee("EMP-7001", SUBJECT);

        mockMvc.perform(get("/api/users/" + id))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("8. Authenticated request succeeds")
    void authenticatedRequest() throws Exception {
        Long id = createEmployee("EMP-7001", SUBJECT);

        mockMvc.perform(get("/api/users/" + id).with(httpBasic(EMPLOYEE_EMAIL, PASSWORD)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("9. Role-based authorization is enforced on the list endpoint")
    void roleBasedAuthorization() throws Exception {
        mockMvc.perform(get("/api/users").with(httpBasic(EMPLOYEE_EMAIL, PASSWORD)))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/users").with(httpBasic(MANAGER_EMAIL, PASSWORD)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("10. Get user by id")
    void getById() throws Exception {
        Long id = createEmployee("EMP-7001", SUBJECT);

        mockMvc.perform(get("/api/users/" + id).with(httpBasic(EMPLOYEE_EMAIL, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id));
    }

    @Test
    @DisplayName("11. Get user by employeeId")
    void getByEmployeeId() throws Exception {
        createEmployee("EMP-7001", SUBJECT);

        mockMvc.perform(get("/api/users/employee/EMP-7001").with(httpBasic(EMPLOYEE_EMAIL, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.employeeId").value("EMP-7001"));
    }

    @Test
    @DisplayName("12. Get user by email")
    void getByEmail() throws Exception {
        createEmployee("EMP-7001", SUBJECT);

        mockMvc.perform(get("/api/users/email/" + SUBJECT).with(httpBasic(EMPLOYEE_EMAIL, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(SUBJECT));
    }

    @Test
    @DisplayName("13. Get all users")
    void getAll() throws Exception {
        createEmployee("EMP-7001", SUBJECT);
        createEmployee("EMP-7002", "e2e.second@accessflow.local");

        mockMvc.perform(get("/api/users").with(httpBasic(MANAGER_EMAIL, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].employeeId", Matchers.hasItems("EMP-7001", "EMP-7002")));
    }

    @Test
    @DisplayName("14. Unknown user returns 404")
    void userNotFound() throws Exception {
        mockMvc.perform(get("/api/users/123456789").with(httpBasic(EMPLOYEE_EMAIL, PASSWORD)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("15. Error response carries timestamp, status, error, message and path")
    void errorResponseStructure() throws Exception {
        mockMvc.perform(get("/api/users/email/ghost@accessflow.local")
                        .with(httpBasic(EMPLOYEE_EMAIL, PASSWORD)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.path").value("/api/users/email/ghost@accessflow.local"))
                .andExpect(jsonPath("$.validationErrors").isArray());
    }

    @Test
    @DisplayName("16. No password appears in any user response")
    void passwordNeverReturned() throws Exception {
        Long id = createEmployee("EMP-7001", SUBJECT);

        String body = mockMvc.perform(get("/api/users/" + id).with(httpBasic(EMPLOYEE_EMAIL, PASSWORD)))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("password").doesNotContain(PASSWORD);
    }

    @Test
    @DisplayName("17. Stored password is a BCrypt hash, not the original")
    void passwordStoredAsBcrypt() {
        Long id = createEmployee("EMP-7001", SUBJECT);

        User stored = userRepository.findById(id).orElseThrow();

        assertThat(stored.getPassword()).isNotEqualTo(PASSWORD).startsWith("$2");
    }
}
