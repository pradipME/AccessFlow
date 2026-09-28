package com.accessflow;

import com.accessflow.entity.User;
import com.accessflow.repository.UserRepository;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@DisplayName("Phase 3 - browser sign-in, session and CSRF")
@EnabledIfEnvironmentVariable(named = "ACCESSFLOW_DB_PASSWORD", matches = ".+")
class WebAuthenticationTest extends IntegrationTestSupport {

    @Autowired
    private UserRepository userRepository;

    @Test
    @DisplayName("The sign-in page is public and renders a form carrying a CSRF token")
    void loginPageIsPublic() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(view().name("login"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(Matchers.containsString("name=\"email\"")))
                .andExpect(content().string(Matchers.containsString("name=\"password\"")))
                // Thymeleaf adds the token to any th:action form; without it every
                // submission would be refused.
                .andExpect(content().string(Matchers.containsString("_csrf")));
    }

    @Test
    @DisplayName("An anonymous visitor is redirected to the sign-in page")
    void anonymousIsRedirectedToLogin() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));

        mockMvc.perform(get("/requests"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));

        mockMvc.perform(get("/requests/new"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    @DisplayName("The review queue is closed before any controller runs")
    void anonymousCannotReachTheReviewQueue() throws Exception {
        mockMvc.perform(get("/requests/review"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    @DisplayName("Signing in with the right password establishes a session and lands on the home page")
    void formLoginSucceeds() throws Exception {
        MvcResult login = mockMvc.perform(post("/login")
                        .param("email", EMPLOYEE_EMAIL)
                        .param("password", PASSWORD)
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"))
                .andReturn();

        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
        assertThat(session).isNotNull();

        // The same session now reaches the authenticated pages, which makes this
        // an end-to-end sign-in rather than a single mocked request.
        mockMvc.perform(get("/").session(session))
                .andExpect(status().isOk())
                .andExpect(view().name("home"))
                .andExpect(content().string(Matchers.containsString(EMPLOYEE_EMAIL)));

        mockMvc.perform(get("/requests").session(session))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("A wrong password returns to the form and reveals nothing about the account")
    void formLoginFails() throws Exception {
        mockMvc.perform(post("/login")
                        .param("email", EMPLOYEE_EMAIL)
                        .param("password", "not-the-password")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?error"));

        mockMvc.perform(get("/login").param("error", ""))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("Invalid email or password")));
    }

    @Test
    @DisplayName("An unknown email fails exactly like a wrong password")
    void formLoginFailsForUnknownEmail() throws Exception {
        mockMvc.perform(post("/login")
                        .param("email", "nobody@accessflow.local")
                        .param("password", PASSWORD)
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?error"));
    }

    @Test
    @DisplayName("A deactivated account cannot sign in, even with the right password")
    void inactiveAccountCannotSignIn() throws Exception {
        Long id = createUser("EMP-WEB-1", "deactivated@accessflow.local", User.Role.EMPLOYEE);
        User stored = userRepository.findById(id).orElseThrow();
        stored.setActive(false);
        userRepository.saveAndFlush(stored);

        mockMvc.perform(post("/login")
                        .param("email", "deactivated@accessflow.local")
                        .param("password", PASSWORD)
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?error"));
    }

    @Test
    @DisplayName("Signing out clears the session and the pages close again")
    void logoutEndsTheSession() throws Exception {
        MvcResult login = mockMvc.perform(post("/login")
                        .param("email", EMPLOYEE_EMAIL)
                        .param("password", PASSWORD)
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
        assertThat(session).isNotNull();

        mockMvc.perform(post("/logout").session(session).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?logout"));

        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    @DisplayName("Signing out without a CSRF token is refused, so another site cannot end a session")
    void logoutRequiresCsrf() throws Exception {
        mockMvc.perform(post("/logout").with(signedInAs(EMPLOYEE_EMAIL)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("A state-changing form post without a CSRF token is refused and writes nothing")
    void postWithoutCsrfIsRefused() throws Exception {
        mockMvc.perform(post("/requests/new")
                        .with(signedInAs(EMPLOYEE_EMAIL))
                        .param("application", "GitHub")
                        .param("justification", "no token here"))
                .andExpect(status().isForbidden());

        assertThat(accessRequestService.getAllRequests()).isEmpty();
    }

    @Test
    @DisplayName("The API still authenticates with HTTP Basic and ignores a browser session")
    void apiKeepsItsOwnTransport() throws Exception {
        MvcResult login = mockMvc.perform(post("/login")
                        .param("email", EMPLOYEE_EMAIL)
                        .param("password", PASSWORD)
                        .with(csrf()))
                .andReturn();
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
        assertThat(session).isNotNull();

        // A signed-in browser must not inherit API access from its cookie, or a
        // stolen session would be a stolen API credential.
        mockMvc.perform(get("/api/access-requests/mine").session(session))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/access-requests/mine").with(httpBasic(EMPLOYEE_EMAIL, PASSWORD)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("A path outside the browser allow-list is refused with a page, not a 500")
    void unlistedPathIsRefusedByTheChain() throws Exception {
        // Nothing is permitted by default in the browser chain, so an unmapped or
        // forgotten path is a refusal decided in the filter chain rather than a
        // controller that quietly renders. The page is rendered by the chain
        // itself, so there is no ModelAndView for MockMvc to name.
        mockMvc.perform(get("/no-such-page").with(signedInAs(EMPLOYEE_EMAIL)))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(Matchers.containsString("403")))
                .andExpect(content().string(Matchers.containsString("/no-such-page")));
    }

    @Test
    @DisplayName("The API keeps answering in JSON, not HTML, now that a UI exists")
    void apiErrorsAreStillJson() throws Exception {
        mockMvc.perform(get("/api/users/employee/EMP-NOT-THERE")
                        .with(httpBasic(EMPLOYEE_EMAIL, PASSWORD)))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().string(Matchers.containsString("\"status\":404")));
    }

    @Test
    @DisplayName("The sign-in page never carries a password or a hash")
    void loginPageLeaksNothing() throws Exception {
        String page = mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(page).doesNotContain(PASSWORD).doesNotContain("$2a$").doesNotContain("$2b$");
    }
}
