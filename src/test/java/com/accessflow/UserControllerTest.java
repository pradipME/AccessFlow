package com.accessflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("Step 8 - UserController")
@EnabledIfEnvironmentVariable(named = "ACCESSFLOW_DB_PASSWORD", matches = ".+")
class UserControllerTest extends IntegrationTestSupport {

    private static final String SUBJECT = "subject.8001@accessflow.local";

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("POST /api/users creates a user and returns 201 with a Location header")
    void createReturns201AndLocation() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRegistrationJson()))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.employeeId").value("EMP-7001"))
                .andReturn();

        long id = objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();

        assertThat(result.getResponse().getHeader("Location")).endsWith("/api/users/" + id);
    }

    @Test
    @DisplayName("GET /api/users returns every user for a manager")
    void getAllReturnsUsers() throws Exception {
        createEmployee("EMP-8002", SUBJECT);

        mockMvc.perform(get("/api/users").with(httpBasic(MANAGER_EMAIL, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[*].employeeId", Matchers.hasItem("EMP-8002")))
                .andExpect(jsonPath("$[*].email", Matchers.hasItem(SUBJECT)));
    }

    @Test
    @DisplayName("GET /api/users/{id} returns the user")
    void getByIdReturnsUser() throws Exception {
        Long id = createEmployee("EMP-8004", SUBJECT);

        mockMvc.perform(get("/api/users/" + id).with(httpBasic(EMPLOYEE_EMAIL, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.employeeId").value("EMP-8004"));
    }

    @Test
    @DisplayName("GET /api/users/employee/{employeeId} returns the user")
    void getByEmployeeIdReturnsUser() throws Exception {
        createEmployee("EMP-8005", SUBJECT);

        mockMvc.perform(get("/api/users/employee/EMP-8005").with(httpBasic(EMPLOYEE_EMAIL, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.employeeId").value("EMP-8005"))
                .andExpect(jsonPath("$.email").value(SUBJECT));
    }

    @Test
    @DisplayName("GET /api/users/email/{email} returns the user")
    void getByEmailReturnsUser() throws Exception {
        createEmployee("EMP-8006", SUBJECT);

        mockMvc.perform(get("/api/users/email/" + SUBJECT).with(httpBasic(EMPLOYEE_EMAIL, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(SUBJECT))
                .andExpect(jsonPath("$.employeeId").value("EMP-8006"));
    }

    @Test
    @DisplayName("Unknown user returns 404 with the standard error body")
    void unknownUserReturns404() throws Exception {
        mockMvc.perform(get("/api/users/" + 987_654_321L).with(httpBasic(EMPLOYEE_EMAIL, PASSWORD)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.path").value("/api/users/987654321"))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    @DisplayName("Duplicate employeeId returns 409 naming the field")
    void duplicateEmployeeIdReturns409() throws Exception {
        createEmployee("EMP-7001", SUBJECT);

        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registrationJson("EMP-7001", "Other", "Person",
                                "other.8001@accessflow.local", PASSWORD, "EMPLOYEE", "Engineering")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Conflict"))
                .andExpect(jsonPath("$.validationErrors[0].field").value("employeeId"));
    }

    @Test
    @DisplayName("Duplicate email returns 409 naming the field")
    void duplicateEmailReturns409() throws Exception {
        createEmployee("EMP-7002", SUBJECT);

        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registrationJson("EMP-7003", "Other", "Person",
                                SUBJECT, PASSWORD, "EMPLOYEE", "Engineering")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.validationErrors[0].field").value("email"));
    }

    @Test
    @DisplayName("No response ever contains a password field")
    void passwordNeverAppearsInAnyResponse() throws Exception {
        Long id = createEmployee("EMP-8007", SUBJECT);

        String[] bodies = {
                mockMvc.perform(get("/api/users/" + id).with(httpBasic(EMPLOYEE_EMAIL, PASSWORD)))
                        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(),
                mockMvc.perform(get("/api/users/employee/EMP-8007").with(httpBasic(EMPLOYEE_EMAIL, PASSWORD)))
                        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(),
                mockMvc.perform(get("/api/users/email/" + SUBJECT).with(httpBasic(EMPLOYEE_EMAIL, PASSWORD)))
                        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(),
                mockMvc.perform(get("/api/users").with(httpBasic(MANAGER_EMAIL, PASSWORD)))
                        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(),
                mockMvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON)
                                .content(registrationJson("EMP-8008", "New", "Person",
                                        "new.8001@accessflow.local", PASSWORD, "EMPLOYEE", "Engineering")))
                        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()
        };

        for (String body : bodies) {
            assertThat(body).doesNotContain("password");
            assertThat(body).doesNotContain(PASSWORD);
            assertThat(body).doesNotContain("$2a$");
            assertThat(body).doesNotContain("$2b$");
        }
    }
}
