package com.accessflow;

import com.accessflow.entity.AccessRequest;
import com.accessflow.entity.User;
import com.accessflow.repository.AccessRequestRepository;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@DisplayName("Phase 3 - filing a request through the browser")
@EnabledIfEnvironmentVariable(named = "ACCESSFLOW_DB_PASSWORD", matches = ".+")
class WebAccessRequestFormTest extends IntegrationTestSupport {

    @Autowired
    private AccessRequestRepository accessRequestRepository;

    @Test
    @DisplayName("The form is rendered with a CSRF token and both fields")
    void formIsRendered() throws Exception {
        mockMvc.perform(get("/requests/new").with(signedInAs(EMPLOYEE_EMAIL)))
                .andExpect(status().isOk())
                .andExpect(view().name("requests/new"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(Matchers.containsString("_csrf")))
                .andExpect(content().string(Matchers.containsString("name=\"application\"")))
                .andExpect(content().string(Matchers.containsString("name=\"justification\"")))
                .andExpect(content().string(Matchers.containsString(EMPLOYEE_EMAIL)));
    }

    @Test
    @DisplayName("Submitting the form files a PENDING request for the signed-in user")
    void submitFilesTheRequest() throws Exception {
        mockMvc.perform(post("/requests/new")
                        .with(signedInAs(EMPLOYEE_EMAIL))
                        .with(csrf())
                        .param("application", "GitHub")
                        .param("justification", "Need commit access to the repo"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/requests/*"));

        assertThat(accessRequestRepository.findAll()).hasSize(1);
        AccessRequest stored = accessRequestRepository.findAll().get(0);

        assertThat(stored.getApplication()).isEqualTo("GitHub");
        assertThat(stored.getJustification()).isEqualTo("Need commit access to the repo");
        assertThat(stored.getStatus()).isEqualTo(AccessRequest.Status.PENDING);
        assertThat(stored.getApplicant().getId()).isEqualTo(idOf(EMPLOYEE_EMAIL));
        assertThat(stored.getReviewedBy()).isNull();
        assertThat(stored.getReviewedAt()).isNull();
        assertThat(stored.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("A blank application or justification re-renders the form and writes nothing")
    void blankValuesAreRefused() throws Exception {
        mockMvc.perform(post("/requests/new")
                        .with(signedInAs(EMPLOYEE_EMAIL))
                        .with(csrf())
                        .param("application", "")
                        .param("justification", ""))
                .andExpect(status().isOk())
                .andExpect(view().name("requests/new"))
                .andExpect(content().string(Matchers.containsString("application is required")))
                .andExpect(content().string(Matchers.containsString("justification is required")));

        assertThat(accessRequestRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("A refused submission keeps what was typed so it does not have to be retyped")
    void refusedSubmissionKeepsTheInput() throws Exception {
        mockMvc.perform(post("/requests/new")
                        .with(signedInAs(EMPLOYEE_EMAIL))
                        .with(csrf())
                        .param("application", "Jira")
                        .param("justification", ""))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("value=\"Jira\"")))
                .andExpect(content().string(Matchers.containsString("justification is required")));

        assertThat(accessRequestRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("Missing form fields are refused like blank ones")
    void missingFieldsAreRefused() throws Exception {
        mockMvc.perform(post("/requests/new")
                        .with(signedInAs(EMPLOYEE_EMAIL))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name("requests/new"))
                .andExpect(content().string(Matchers.containsString("application is required")));

        assertThat(accessRequestRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("An over-long justification is refused with the field message")
    void overLongJustificationIsRefused() throws Exception {
        mockMvc.perform(post("/requests/new")
                        .with(signedInAs(EMPLOYEE_EMAIL))
                        .with(csrf())
                        .param("application", "AWS")
                        .param("justification", "x".repeat(1001)))
                .andExpect(status().isOk())
                .andExpect(content().string(
                        Matchers.containsString("justification must not exceed 1000 characters")));

        assertThat(accessRequestRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("A justification of exactly the maximum length is accepted")
    void maximumLengthIsAccepted() throws Exception {
        String justification = "x".repeat(1000);

        mockMvc.perform(post("/requests/new")
                        .with(signedInAs(EMPLOYEE_EMAIL))
                        .with(csrf())
                        .param("application", "AWS")
                        .param("justification", justification))
                .andExpect(status().is3xxRedirection());

        assertThat(accessRequestRepository.findAll().get(0).getJustification()).isEqualTo(justification);
    }

    @Test
    @DisplayName("A form cannot name a different applicant")
    void applicantComesFromTheSession() throws Exception {
        mockMvc.perform(post("/requests/new")
                        .with(signedInAs(EMPLOYEE_EMAIL))
                        .with(csrf())
                        .param("application", "AWS")
                        .param("justification", "mine")
                        .param("applicant", MANAGER_EMAIL)
                        .param("applicantId", String.valueOf(idOf(MANAGER_EMAIL)))
                        .param("userId", "1"))
                .andExpect(status().is3xxRedirection());

        AccessRequest stored = accessRequestRepository.findAll().get(0);
        assertThat(stored.getApplicant().getId()).isEqualTo(idOf(EMPLOYEE_EMAIL));
    }

    @Test
    @DisplayName("A manager filing a request is recorded as the applicant, not as a reviewer")
    void aManagerIsStillJustTheApplicant() throws Exception {
        mockMvc.perform(post("/requests/new")
                        .with(signedInAs(MANAGER_EMAIL))
                        .with(csrf())
                        .param("application", "VPN")
                        .param("justification", "Working from another country"))
                .andExpect(status().is3xxRedirection());

        AccessRequest stored = accessRequestRepository.findAll().get(0);
        assertThat(stored.getApplicant().getRole()).isEqualTo(User.Role.MANAGER);
        assertThat(stored.getReviewedBy()).isNull();
        assertThat(stored.getStatus()).isEqualTo(AccessRequest.Status.PENDING);
    }

    @Test
    @DisplayName("The submitted justification is escaped, never rendered as markup")
    void justificationIsEscaped() throws Exception {
        Long id = createAccessRequest(idOf(EMPLOYEE_EMAIL), "GitHub",
                "<script>alert('x')</script>");

        String page = mockMvc.perform(get("/requests/" + id).with(signedInAs(EMPLOYEE_EMAIL)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(page).contains("&lt;script&gt;").doesNotContain("<script>alert");
    }

    @Test
    @DisplayName("No page of the request flow contains a password or a hash")
    void pagesLeakNoCredentials() throws Exception {
        Long id = createAccessRequest(idOf(EMPLOYEE_EMAIL), "AWS", "checking for leaks");

        String[] pages = {
                mockMvc.perform(get("/").with(signedInAs(EMPLOYEE_EMAIL)))
                        .andReturn().getResponse().getContentAsString(),
                mockMvc.perform(get("/requests/new").with(signedInAs(EMPLOYEE_EMAIL)))
                        .andReturn().getResponse().getContentAsString(),
                mockMvc.perform(get("/requests").with(signedInAs(EMPLOYEE_EMAIL)))
                        .andReturn().getResponse().getContentAsString(),
                mockMvc.perform(get("/requests/" + id).with(signedInAs(EMPLOYEE_EMAIL)))
                        .andReturn().getResponse().getContentAsString(),
                mockMvc.perform(get("/requests/review").with(signedInAs(MANAGER_EMAIL)))
                        .andReturn().getResponse().getContentAsString()
        };

        for (String page : pages) {
            assertThat(page).doesNotContain(PASSWORD)
                    .doesNotContain("$2a$")
                    .doesNotContain("$2b$");
        }
    }
}
