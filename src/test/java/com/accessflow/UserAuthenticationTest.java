package com.accessflow;

import com.accessflow.entity.User;
import com.accessflow.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("Step 12 - authentication and role authorization")
@EnabledIfEnvironmentVariable(named = "ACCESSFLOW_DB_PASSWORD", matches = ".+")
class UserAuthenticationTest extends IntegrationTestSupport {

    private static final String SUBJECT = "authz.subject@accessflow.local";

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    @DisplayName("Passwords are stored as BCrypt hashes, never plaintext")
    void passwordIsStoredAsBcryptHash() {
        Long id = createEmployee("EMP-9201", SUBJECT);

        User stored = userRepository.findById(id).orElseThrow();

        assertThat(stored.getPassword()).isNotEqualTo(PASSWORD);
        assertThat(stored.getPassword()).startsWith("$2");
        assertThat(passwordEncoder.matches(PASSWORD, stored.getPassword())).isTrue();
    }

    @Test
    @DisplayName("Login succeeds with email and password and reports the role")
    void loginSucceeds() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(EMPLOYEE_EMAIL, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(EMPLOYEE_EMAIL))
                .andExpect(jsonPath("$.role").value("EMPLOYEE"))
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.authorities[0]").value("ROLE_EMPLOYEE"));
    }

    @Test
    @DisplayName("Login fails with a wrong password")
    void loginFailsWithWrongPassword() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(EMPLOYEE_EMAIL, "wrong-password")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid email or password"));
    }

    @Test
    @DisplayName("Login fails for an unknown email without revealing which part was wrong")
    void loginFailsForUnknownEmail() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("nobody@accessflow.local", PASSWORD)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid email or password"));
    }

    @Test
    @DisplayName("A protected request without credentials returns 401")
    void protectedRequestRequiresAuthentication() throws Exception {
        Long id = createEmployee("EMP-9204", SUBJECT);

        mockMvc.perform(get("/api/users/" + id))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").value("Authentication required"));
    }

    @Test
    @DisplayName("A protected request with valid credentials succeeds")
    void authenticatedRequestSucceeds() throws Exception {
        Long id = createEmployee("EMP-9205", SUBJECT);

        mockMvc.perform(get("/api/users/" + id).with(httpBasic(EMPLOYEE_EMAIL, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id));
    }

    @Test
    @DisplayName("A deactivated account is refused at the API too, not just in the browser")
    void deactivatedAccountCannotAuthenticateAgainstTheApi() throws Exception {
        // The browser test covers the form-login path. The API chain authenticates
        // through the same UserDetailsService, so a disabled account has to be
        // refused there as well - otherwise deactivating a leaver would only close
        // the UI and leave their Basic credentials working.
        String email = "deactivated.api@accessflow.local";
        Long id = createEmployee("EMP-9205D", email);

        User stored = userRepository.findById(id).orElseThrow();
        stored.setActive(false);
        userRepository.saveAndFlush(stored);

        // The right password, refused because the account is disabled.
        mockMvc.perform(get("/api/users/" + id).with(httpBasic(email, PASSWORD)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));

        // The seeded employee is still able to sign in, so the refusal above is the
        // account and not a broken filter chain.
        mockMvc.perform(get("/api/users/" + id).with(httpBasic(EMPLOYEE_EMAIL, PASSWORD)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("An unmapped path under /api is denied by the chain, not left open")
    void unmappedApiPathIsDenied() throws Exception {
        // anyRequest().denyAll() is the rule that makes the API chain closed by
        // default: a route nobody has declared yet is refused rather than falling
        // through to whatever the servlet would do with it. It is asserted here
        // because it is the one rule in the chain with no controller behind it, so
        // no endpoint test can reach it.
        mockMvc.perform(get("/api/does-not-exist").with(httpBasic(SUPER_ADMIN_EMAIL, PASSWORD)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.message").value(
                        "You do not have permission to perform this action"));

        // A SUPER_ADMIN is refused here too, so this is the chain closing and not a
        // role check.
        mockMvc.perform(post("/api/internal/reset").with(httpBasic(SUPER_ADMIN_EMAIL, PASSWORD)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("The API chain is reached before the browser chain and does not accept a UI session")
    void apiIgnoresTheBrowserSession() throws Exception {
        // A signed-in browser session must not be usable as an API credential: the
        // API chain is stateless and never reads the session, so a cookie obtained
        // by signing in through the login form is worth nothing there.
        //
        // This signs in for real through POST /login rather than injecting a
        // principal, because a pre-authenticated request would be accepted by
        // either chain and would prove nothing about how the cookie is treated.
        Long id = createEmployee("EMP-9205S", SUBJECT);

        MvcResult login = mockMvc.perform(post("/login")
                        .param("email", EMPLOYEE_EMAIL)
                        .param("password", PASSWORD)
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        MockHttpSession uiSession = (MockHttpSession) login.getRequest().getSession(false);
        assertThat(uiSession).isNotNull();

        // The session opens the browser pages.
        mockMvc.perform(get("/").session(uiSession))
                .andExpect(status().isOk());

        // It opens nothing on the API.
        mockMvc.perform(get("/api/users/" + id).session(uiSession))
                .andExpect(status().isUnauthorized());

        // Basic auth on the same request works, so the refusal above is the session
        // being ignored and not the endpoint being closed.
        mockMvc.perform(get("/api/users/" + id).with(httpBasic(EMPLOYEE_EMAIL, PASSWORD)))
                .andExpect(status().isOk());
    }


    @Test
    @DisplayName("An EMPLOYEE may read a single user but not the full list")
    void employeePermissions() throws Exception {
        Long id = createEmployee("EMP-9206", SUBJECT);

        mockMvc.perform(get("/api/users/" + id).with(httpBasic(EMPLOYEE_EMAIL, PASSWORD)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/users").with(httpBasic(EMPLOYEE_EMAIL, PASSWORD)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    @DisplayName("A MANAGER may read the full list")
    void managerPermissions() throws Exception {
        mockMvc.perform(get("/api/users").with(httpBasic(MANAGER_EMAIL, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    @DisplayName("An IT_ADMIN may read the full list")
    void itAdminPermissions() throws Exception {
        mockMvc.perform(get("/api/users").with(httpBasic(IT_ADMIN_EMAIL, PASSWORD)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/users").with(httpBasic(EMPLOYEE_EMAIL, PASSWORD)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("A SUPER_ADMIN may read the full list")
    void superAdminPermissions() throws Exception {
        mockMvc.perform(get("/api/users").with(httpBasic(SUPER_ADMIN_EMAIL, PASSWORD)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Public registration cannot self-assign a privileged role")
    void publicRegistrationCannotEscalateRole() throws Exception {
        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registrationJson("EMP-9210", "Sneaky", "Person",
                                "sneaky@accessflow.local", PASSWORD, "SUPER_ADMIN", null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("EMPLOYEE"));

        assertThat(userRepository.findByEmail("sneaky@accessflow.local").orElseThrow().getRole())
                .isEqualTo(User.Role.EMPLOYEE);
    }

    @Test
    @DisplayName("A SUPER_ADMIN may create a user with an explicit role")
    void superAdminMayAssignRole() throws Exception {
        mockMvc.perform(post("/api/users").with(httpBasic(SUPER_ADMIN_EMAIL, PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registrationJson("EMP-9212", "Hired", "Manager",
                                "hired.mgr@accessflow.local", PASSWORD, "MANAGER", null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("MANAGER"));
    }

    @Test
    @DisplayName("No login or user response ever contains a password or hash")
    void passwordsAreNeverExposed() throws Exception {
        Long id = createEmployee("EMP-9213", SUBJECT);

        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(EMPLOYEE_EMAIL, PASSWORD)))
                .andExpect(status().isOk()).andReturn();

        MvcResult user = mockMvc.perform(get("/api/users/" + id).with(httpBasic(EMPLOYEE_EMAIL, PASSWORD)))
                .andExpect(status().isOk()).andReturn();

        for (MvcResult result : new MvcResult[]{login, user}) {
            String body = result.getResponse().getContentAsString();
            assertThat(body).doesNotContain(PASSWORD);
            assertThat(body).doesNotContain("$2a$");
            assertThat(body).doesNotContain("$2b$");
            assertThat(body).doesNotContain("password");
        }
    }

    private String loginBody(String email, String password) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
    }
}
