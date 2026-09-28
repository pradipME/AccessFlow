package com.accessflow;

import com.accessflow.entity.User;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("Phase 2 - access request API")
@EnabledIfEnvironmentVariable(named = "ACCESSFLOW_DB_PASSWORD", matches = ".+")
class AccessRequestApiTest extends IntegrationTestSupport {

    @Test
    @DisplayName("POST /api/access-requests files a request and returns 201 with a Location header")
    void createReturns201() throws Exception {
        mockMvc.perform(post("/api/access-requests")
                        .with(httpBasic(EMPLOYEE_EMAIL, PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(accessRequestJson("GitHub", "Need commit access")))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.application").value("GitHub"))
                .andExpect(jsonPath("$.justification").value("Need commit access"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.applicant.email").value(EMPLOYEE_EMAIL))
                .andExpect(jsonPath("$.reviewedBy").doesNotExist());
    }

    @Test
    @DisplayName("A request body cannot claim to be a different applicant")
    void applicantComesFromTheAuthentication() throws Exception {
        mockMvc.perform(post("/api/access-requests")
                        .with(httpBasic(EMPLOYEE_EMAIL, PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("application", "AWS", "justification", "mine",
                                "applicant", MANAGER_EMAIL, "applicantId", "999")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.applicant.email").value(EMPLOYEE_EMAIL));
    }

    @Test
    @DisplayName("Creating a request without authentication is refused")
    void createRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/access-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(accessRequestJson("GitHub", "anonymous")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("A missing application or justification is a 400 with field errors")
    void createValidatesRequiredFields() throws Exception {
        mockMvc.perform(post("/api/access-requests")
                        .with(httpBasic(EMPLOYEE_EMAIL, PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(accessRequestJson(null, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors").isNotEmpty())
                .andExpect(jsonPath("$.validationErrors[?(@.field == 'application')]").exists())
                .andExpect(jsonPath("$.validationErrors[?(@.field == 'justification')]").exists());

        mockMvc.perform(post("/api/access-requests")
                        .with(httpBasic(EMPLOYEE_EMAIL, PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(accessRequestJson("", "")))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("An over-long justification is a 400")
    void createRejectsOverLongJustification() throws Exception {
        mockMvc.perform(post("/api/access-requests")
                        .with(httpBasic(EMPLOYEE_EMAIL, PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(accessRequestJson("GitHub", "x".repeat(1001))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors[?(@.field == 'justification')]").exists());
    }

    @Test
    @DisplayName("GET /api/access-requests/mine returns only the caller's requests")
    void mineReturnsOwnRequests() throws Exception {
        createAccessRequest(idOf(EMPLOYEE_EMAIL), "GitHub", "mine");
        createAccessRequest(idOf(MANAGER_EMAIL), "Jira", "not mine");

        mockMvc.perform(get("/api/access-requests/mine").with(httpBasic(EMPLOYEE_EMAIL, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size()").value(1))
                .andExpect(jsonPath("$[0].application").value("GitHub"))
                .andExpect(jsonPath("$[0].applicant.email").value(EMPLOYEE_EMAIL));
    }

    @Test
    @DisplayName("GET /api/access-requests lists every request for a reviewer")
    void listAllForReviewer() throws Exception {
        createAccessRequest(idOf(EMPLOYEE_EMAIL), "GitHub", "one");

        mockMvc.perform(get("/api/access-requests").with(httpBasic(MANAGER_EMAIL, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].application", Matchers.hasItem("GitHub")));

        mockMvc.perform(get("/api/access-requests").with(httpBasic(IT_ADMIN_EMAIL, PASSWORD)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/access-requests").with(httpBasic(SUPER_ADMIN_EMAIL, PASSWORD)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("An employee is forbidden from the full request list")
    void employeeCannotListAllRequests() throws Exception {
        mockMvc.perform(get("/api/access-requests").with(httpBasic(EMPLOYEE_EMAIL, PASSWORD)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    @DisplayName("GET /api/access-requests/status/{status} filters for reviewers only")
    void filterByStatus() throws Exception {
        Long employeeId = idOf(EMPLOYEE_EMAIL);
        Long approved = createAccessRequest(employeeId, "AWS", "to approve");
        createAccessRequest(employeeId, "Jira", "still pending");
        approveEveryStage(approved);

        mockMvc.perform(get("/api/access-requests/status/APPROVED").with(httpBasic(MANAGER_EMAIL, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", Matchers.hasItem(approved.intValue())));

        mockMvc.perform(get("/api/access-requests/status/PENDING").with(httpBasic(MANAGER_EMAIL, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].status", Matchers.everyItem(Matchers.is("PENDING"))));

        mockMvc.perform(get("/api/access-requests/status/PENDING").with(httpBasic(EMPLOYEE_EMAIL, PASSWORD)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("An unknown status value is a 400, not a 500")
    void unknownStatusIsBadRequest() throws Exception {
        mockMvc.perform(get("/api/access-requests/status/BANANA").with(httpBasic(MANAGER_EMAIL, PASSWORD)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /api/access-requests/{id} returns the request to its applicant and to reviewers")
    void getById() throws Exception {
        Long id = createAccessRequest(idOf(EMPLOYEE_EMAIL), "VPN", "traveling");

        mockMvc.perform(get("/api/access-requests/" + id).with(httpBasic(EMPLOYEE_EMAIL, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.application").value("VPN"));

        mockMvc.perform(get("/api/access-requests/" + id).with(httpBasic(MANAGER_EMAIL, PASSWORD)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("An employee cannot read another employee's request")
    void employeeCannotReadAnotherRequest() throws Exception {
        Long id = createAccessRequest(idOf(EMPLOYEE_EMAIL), "AWS", "not yours");
        Long otherEmployeeId = createUser("EMP-2ND", "actor.employee2@accessflow.local",
                User.Role.EMPLOYEE);

        mockMvc.perform(get("/api/access-requests/" + id)
                        .with(httpBasic("actor.employee2@accessflow.local", PASSWORD)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
        assertThat(id).isNotEqualTo(otherEmployeeId);
    }

    @Test
    @DisplayName("An unknown request id returns 404 with the standard error body")
    void unknownRequestIs404() throws Exception {
        mockMvc.perform(get("/api/access-requests/99999999").with(httpBasic(MANAGER_EMAIL, PASSWORD)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.path").value("/api/access-requests/99999999"))
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    @DisplayName("PATCH approve records a stage approval and leaves the request PENDING until stage 2")
    void approveEndpoint() throws Exception {
        // One approval is one stage. The request is not resolved by it, so the
        // response says PENDING and carries no reviewedBy: nothing about the
        // request as a whole has been decided yet. The stage rows show what did
        // happen.
        Long id = createAccessRequest(idOf(EMPLOYEE_EMAIL), "GitHub", "need access");

        mockMvc.perform(patch("/api/access-requests/" + id + "/approve")
                        .with(httpBasic(MANAGER_EMAIL, PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("notes", "business need")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.reviewedBy").doesNotExist())
                .andExpect(jsonPath("$.reviewedAt").doesNotExist())
                .andExpect(jsonPath("$.stages.length()").value(2))
                .andExpect(jsonPath("$.stages[0].order").value(1))
                .andExpect(jsonPath("$.stages[0].requiredRole").value("MANAGER"))
                .andExpect(jsonPath("$.stages[0].status").value("APPROVED"))
                .andExpect(jsonPath("$.stages[0].notes").value("business need"))
                .andExpect(jsonPath("$.stages[0].decidedBy.email").value(MANAGER_EMAIL))
                .andExpect(jsonPath("$.stages[0].decidedAt").exists())
                .andExpect(jsonPath("$.stages[1].order").value(2))
                .andExpect(jsonPath("$.stages[1].requiredRole").value("IT_ADMIN"))
                .andExpect(jsonPath("$.stages[1].status").value("PENDING"))
                .andExpect(jsonPath("$.stages[1].decidedBy").doesNotExist());
    }

    @Test
    @DisplayName("The second approval resolves the request and names the decider of the last stage")
    void approveEndpointResolvesOnTheSecondStage() throws Exception {
        Long id = createAccessRequest(idOf(EMPLOYEE_EMAIL), "GitHub", "need access");
        approveFirstStageOnly(id);

        mockMvc.perform(patch("/api/access-requests/" + id + "/approve")
                        .with(httpBasic(IT_ADMIN_EMAIL, PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("notes", "provisioning approved")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.reviewNotes").value("provisioning approved"))
                .andExpect(jsonPath("$.reviewedBy.email").value(IT_ADMIN_EMAIL))
                .andExpect(jsonPath("$.reviewedAt").exists())
                .andExpect(jsonPath("$.stages[1].status").value("APPROVED"));
    }

    @Test
    @DisplayName("The serialised response carries the stage list and nothing derived from it")
    void responseCarriesStagesAndNotTheDerivedNextStage() throws Exception {
        // nextStage() is a method on the record rather than a component, and the
        // API contract depends on it staying that way: it is a convenience for
        // the pages, computed on demand. If it ever became a serialised property
        // every existing client would see a new field appear in the body of a
        // response they already parse, so this asserts the field is absent
        // rather than only checking that the stages are present.
        Long id = createAccessRequest(idOf(EMPLOYEE_EMAIL), "GitHub", "need access");

        mockMvc.perform(get("/api/access-requests/" + id)
                        .with(httpBasic(EMPLOYEE_EMAIL, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stages.length()").value(2))
                .andExpect(jsonPath("$.nextStage").doesNotExist());

        // And on a resolved request, where the derivation is null: still absent
        // rather than emitted as a null field.
        approveEveryStage(id);

        mockMvc.perform(get("/api/access-requests/" + id)
                        .with(httpBasic(EMPLOYEE_EMAIL, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.nextStage").doesNotExist());
    }

    @Test
    @DisplayName("Approving without a body is allowed at every stage")
    void approveWithoutBody() throws Exception {
        Long id = createAccessRequest(idOf(EMPLOYEE_EMAIL), "GitHub", "no notes needed");

        mockMvc.perform(patch("/api/access-requests/" + id + "/approve")
                        .with(httpBasic(MANAGER_EMAIL, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.stages[0].notes").doesNotExist());

        mockMvc.perform(patch("/api/access-requests/" + id + "/approve")
                        .with(httpBasic(IT_ADMIN_EMAIL, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.reviewNotes").doesNotExist());
    }

    @Test
    @DisplayName("A reviewer cannot approve a stage that asks for a different role")
    void approveOutOfTurnIsForbidden() throws Exception {
        // The chain is role-ordered. An IT_ADMIN reaching for stage 1, or a
        // MANAGER reaching for stage 2, is refused with the same 403 the rest of
        // the API uses - the chain is the gate, not a new error shape.
        Long id = createAccessRequest(idOf(EMPLOYEE_EMAIL), "AWS", "out of turn");

        mockMvc.perform(patch("/api/access-requests/" + id + "/approve")
                        .with(httpBasic(IT_ADMIN_EMAIL, PASSWORD)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));

        approveFirstStageOnly(id);

        mockMvc.perform(patch("/api/access-requests/" + id + "/approve")
                        .with(httpBasic(MANAGER_EMAIL, PASSWORD)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));

        // Neither attempt resolved anything.
        mockMvc.perform(get("/api/access-requests/" + id).with(httpBasic(EMPLOYEE_EMAIL, PASSWORD)))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    @DisplayName("PATCH reject requires a reason")
    void rejectRequiresAReason() throws Exception {
        Long id = createAccessRequest(idOf(EMPLOYEE_EMAIL), "HRMS", "payroll details");

        mockMvc.perform(patch("/api/access-requests/" + id + "/reject")
                        .with(httpBasic(MANAGER_EMAIL, PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("notes", "")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors[?(@.field == 'notes')]").exists());

        mockMvc.perform(patch("/api/access-requests/" + id + "/reject")
                        .with(httpBasic(MANAGER_EMAIL, PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PATCH reject with a reason moves the request to REJECTED")
    void rejectEndpoint() throws Exception {
        Long id = createAccessRequest(idOf(EMPLOYEE_EMAIL), "HRMS", "payroll details");

        mockMvc.perform(patch("/api/access-requests/" + id + "/reject")
                        .with(httpBasic(MANAGER_EMAIL, PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("notes", "no business need")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.reviewNotes").value("no business need"));
    }

    @Test
    @DisplayName("Reviewing a resolved request returns 409 and leaves the last decision in place")
    void doubleReviewIsConflict() throws Exception {
        // The whole chain has been run, so a further decision has nothing left to
        // apply to. This is the same 409 a second review has always returned.
        Long id = createAccessRequest(idOf(EMPLOYEE_EMAIL), "Jira", "sprint work");
        approveEveryStage(id);

        mockMvc.perform(patch("/api/access-requests/" + id + "/approve")
                        .with(httpBasic(MANAGER_EMAIL, PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("notes", "again")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(Matchers.containsString("APPROVED")));

        mockMvc.perform(get("/api/access-requests/" + id).with(httpBasic(MANAGER_EMAIL, PASSWORD)))
                .andExpect(jsonPath("$.reviewNotes").value("stage 2 ok"));
    }

    @Test
    @DisplayName("An applicant cannot approve their own request")
    void selfApprovalIsForbidden() throws Exception {
        // A manager files their own request, so the role check passes but
        // separation of duties must still refuse it.
        Long managerId = idOf(MANAGER_EMAIL);
        Long id = createAccessRequest(managerId, "AWS", "I would like this myself");

        mockMvc.perform(patch("/api/access-requests/" + id + "/approve")
                        .with(httpBasic(MANAGER_EMAIL, PASSWORD)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.message").value(
                        "You cannot review your own access request"));
    }

    @Test
    @DisplayName("An IT_ADMIN applicant cannot decide their own request, at either stage")
    void itAdminApplicantCannotApproveOwnRequest() throws Exception {
        // The self-approval rule has to hold for the roles that can actually
        // review, not only for MANAGER. An IT_ADMIN applicant is refused at both
        // stages: at stage 1 the role check would have refused them anyway, and
        // at stage 2 the role matches, so only separation of duties stands between
        // them and approving their own request.
        Long itAdminId = idOf(IT_ADMIN_EMAIL);
        Long id = createAccessRequest(itAdminId, "Grafana", "I administer this already");

        mockMvc.perform(patch("/api/access-requests/" + id + "/approve")
                        .with(httpBasic(IT_ADMIN_EMAIL, PASSWORD)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(
                        "You cannot review your own access request"));

        approveFirstStageOnly(id);

        mockMvc.perform(patch("/api/access-requests/" + id + "/approve")
                        .with(httpBasic(IT_ADMIN_EMAIL, PASSWORD)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(
                        "You cannot review your own access request"));

        // The request is untouched by both attempts.
        mockMvc.perform(get("/api/access-requests/" + id).with(httpBasic(MANAGER_EMAIL, PASSWORD)))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.stages[0].status").value("APPROVED"))
                .andExpect(jsonPath("$.stages[1].status").value("PENDING"))
                .andExpect(jsonPath("$.stages[1].decidedBy").doesNotExist());
    }

    @Test
    @DisplayName("A SUPER_ADMIN applicant cannot decide their own request, at either stage")
    void superAdminApplicantCannotApproveOwnRequest() throws Exception {
        // SUPER_ADMIN may review either stage, so without the self-approval check
        // this applicant would be able to drive their own request all the way to
        // APPROVED with nothing but their own credentials. This is the case the
        // rule most protects.
        Long superAdminId = idOf(SUPER_ADMIN_EMAIL);
        Long id = createAccessRequest(superAdminId, "Production", "unrestricted access");

        mockMvc.perform(patch("/api/access-requests/" + id + "/approve")
                        .with(httpBasic(SUPER_ADMIN_EMAIL, PASSWORD)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(
                        "You cannot review your own access request"));

        approveFirstStageOnly(id);

        mockMvc.perform(patch("/api/access-requests/" + id + "/approve")
                        .with(httpBasic(SUPER_ADMIN_EMAIL, PASSWORD)))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/access-requests/" + id).with(httpBasic(IT_ADMIN_EMAIL, PASSWORD)))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    @DisplayName("A SUPER_ADMIN may approve either stage on somebody else's request")
    void superAdminCanApproveEitherStage() throws Exception {
        // SUPER_ADMIN is an override for the stage roles, so the API has to let one
        // caller do both stages - otherwise the override exists in the service but
        // is unreachable over HTTP. Still two decisions, and only the second one
        // resolves the request.
        Long id = createAccessRequest(idOf(EMPLOYEE_EMAIL), "Terraform", "state access");

        mockMvc.perform(patch("/api/access-requests/" + id + "/approve")
                        .with(httpBasic(SUPER_ADMIN_EMAIL, PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("notes", "root cleared stage 1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.reviewedBy").doesNotExist())
                .andExpect(jsonPath("$.stages[0].order").value(1))
                .andExpect(jsonPath("$.stages[0].status").value("APPROVED"))
                .andExpect(jsonPath("$.stages[0].decidedBy.email").value(SUPER_ADMIN_EMAIL))
                .andExpect(jsonPath("$.stages[1].status").value("PENDING"));

        mockMvc.perform(patch("/api/access-requests/" + id + "/approve")
                        .with(httpBasic(SUPER_ADMIN_EMAIL, PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("notes", "root cleared stage 2")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.reviewedBy.email").value(SUPER_ADMIN_EMAIL))
                .andExpect(jsonPath("$.reviewNotes").value("root cleared stage 2"))
                .andExpect(jsonPath("$.stages[1].status").value("APPROVED"));
    }

    @Test
    @DisplayName("A SUPER_ADMIN may reject at either stage and the request stops there")
    void superAdminCanRejectEitherStage() throws Exception {
        // The override applies to rejection as well, and a rejection is terminal
        // whichever stage it lands on - so stage 2 must be abandoned, not left
        // pending for somebody else to decide.
        Long atStageOne = createAccessRequest(idOf(EMPLOYEE_EMAIL), "Vault", "secrets access");

        mockMvc.perform(patch("/api/access-requests/" + atStageOne + "/reject")
                        .with(httpBasic(SUPER_ADMIN_EMAIL, PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("notes", "not needed at stage 1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.reviewedBy.email").value(SUPER_ADMIN_EMAIL))
                .andExpect(jsonPath("$.stages[0].status").value("REJECTED"))
                .andExpect(jsonPath("$.stages[0].decidedBy.email").value(SUPER_ADMIN_EMAIL));

        Long atStageTwo = createAccessRequest(idOf(EMPLOYEE_EMAIL), "Vault", "secrets access, stage 2");
        approveFirstStageOnly(atStageTwo);

        mockMvc.perform(patch("/api/access-requests/" + atStageTwo + "/reject")
                        .with(httpBasic(SUPER_ADMIN_EMAIL, PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("notes", "not needed at stage 2")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.stages[0].status").value("APPROVED"))
                .andExpect(jsonPath("$.stages[1].status").value("REJECTED"));

        // A resolved request cannot be decided again, whoever asks.
        mockMvc.perform(patch("/api/access-requests/" + atStageTwo + "/approve")
                        .with(httpBasic(SUPER_ADMIN_EMAIL, PASSWORD)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    @DisplayName("An employee cannot approve another employee's request")
    void employeeCannotApprove() throws Exception {
        Long id = createAccessRequest(idOf(MANAGER_EMAIL), "AWS", "manager's request");
        createUser("EMP-2ND", "actor.employee2@accessflow.local",
                User.Role.EMPLOYEE);

        mockMvc.perform(patch("/api/access-requests/" + id + "/approve")
                        .with(httpBasic("actor.employee2@accessflow.local", PASSWORD)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    @DisplayName("Reviewing an unknown request returns 404")
    void reviewUnknownRequestIs404() throws Exception {
        mockMvc.perform(patch("/api/access-requests/99999999/approve")
                        .with(httpBasic(MANAGER_EMAIL, PASSWORD)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    @DisplayName("No access request response contains a password or hash")
    void noPasswordIsEverReturned() throws Exception {
        Long id = createAccessRequest(idOf(EMPLOYEE_EMAIL), "AWS", "checking for leaks");
        approveEveryStage(id);

        String[] bodies = {
                mockMvc.perform(get("/api/access-requests/" + id).with(httpBasic(EMPLOYEE_EMAIL, PASSWORD)))
                        .andReturn().getResponse().getContentAsString(),
                mockMvc.perform(get("/api/access-requests/mine").with(httpBasic(EMPLOYEE_EMAIL, PASSWORD)))
                        .andReturn().getResponse().getContentAsString(),
                mockMvc.perform(get("/api/access-requests").with(httpBasic(MANAGER_EMAIL, PASSWORD)))
                        .andReturn().getResponse().getContentAsString(),
                mockMvc.perform(post("/api/access-requests")
                                .with(httpBasic(EMPLOYEE_EMAIL, PASSWORD))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(accessRequestJson("Jira", "checking creation too")))
                        .andReturn().getResponse().getContentAsString()
        };

        for (String body : bodies) {
            assertThat(body).doesNotContain("password")
                    .doesNotContain(PASSWORD)
                    .doesNotContain("$2a$")
                    .doesNotContain("$2b$");
        }
    }

    @Test
    @DisplayName("A validation error never echoes the request body back")
    void validationErrorDoesNotLeakBody() throws Exception {
        String body = mockMvc.perform(post("/api/access-requests")
                        .with(httpBasic(EMPLOYEE_EMAIL, PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(accessRequestJson("", "")))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(PASSWORD);
    }
}
