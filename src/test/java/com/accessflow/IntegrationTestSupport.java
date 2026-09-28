package com.accessflow;

import com.accessflow.dto.AccessRequestCreateRequest;
import com.accessflow.entity.User;
import com.accessflow.security.AccessFlowUserDetails;
import com.accessflow.service.AccessRequestService;
import com.accessflow.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

/**
 * Shared harness for the MySQL-backed API and UI tests.
 *
 * Every test runs inside a transaction that rolls back, so each method starts
 * from an empty table and fixed identifiers are safe to reuse.
 *
 * Subclasses must each carry
 * {@code @EnabledIfEnvironmentVariable(named = "ACCESSFLOW_DB_PASSWORD", matches = ".+")}.
 * That annotation is not inherited from a superclass, so declaring it here would
 * silently leave the subclass ungated and every test would fail on a missing
 * credential instead of skipping. The tests are never disabled to force a green
 * build: without the credential they report as skipped.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
public abstract class IntegrationTestSupport {

    protected static final String PASSWORD = "Str0ngPassw0rd!";

    /**
     * One actor per role, seeded for every test.
     *
     * Each test rolls back, so a user created in one method is invisible to the
     * next. Authenticating as a user that only some other test created is the
     * easiest way to get a spurious 401, so the four roles are seeded up front
     * and every test authenticates as one of these.
     */
    protected static final String EMPLOYEE_EMAIL = "actor.employee@accessflow.local";
    protected static final String MANAGER_EMAIL = "actor.manager@accessflow.local";
    protected static final String IT_ADMIN_EMAIL = "actor.itadmin@accessflow.local";
    protected static final String SUPER_ADMIN_EMAIL = "actor.superadmin@accessflow.local";

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected UserService userService;

    @Autowired
    protected AccessRequestService accessRequestService;

    @BeforeEach
    void seedStandardActors() {
        createEmployee("ACTOR-EMP", EMPLOYEE_EMAIL);
        createUser("ACTOR-MGR", MANAGER_EMAIL, User.Role.MANAGER);
        createUser("ACTOR-IT", IT_ADMIN_EMAIL, User.Role.IT_ADMIN);
        createUser("ACTOR-ROOT", SUPER_ADMIN_EMAIL, User.Role.SUPER_ADMIN);
    }

    protected Long createUser(String employeeId, String email, User.Role role) {
        User user = new User();
        user.setEmployeeId(employeeId);
        user.setFirstName("Anita");
        user.setLastName("Sharma");
        user.setEmail(email);
        user.setPassword(PASSWORD);
        user.setRole(role);
        user.setDepartment("Engineering");
        return userService.createUser(user).getId();
    }

    protected Long createEmployee(String employeeId, String email) {
        return createUser(employeeId, email, User.Role.EMPLOYEE);
    }

    /**
     * Builds a JSON object from alternating name/value pairs. A null value omits
     * the field entirely, which is how a "missing" field is exercised as
     * distinct from a blank one.
     */
    protected String json(String... namesAndValues) {
        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        for (int i = 0; i < namesAndValues.length; i += 2) {
            String value = namesAndValues[i + 1];
            if (value == null) {
                continue;
            }
            if (!first) {
                json.append(',');
            }
            json.append('"').append(namesAndValues[i]).append("\":\"").append(value).append('"');
            first = false;
        }
        return json.append('}').toString();
    }

    protected String registrationJson(String employeeId, String firstName, String lastName,
                                      String email, String password, String role, String department) {
        return json("employeeId", employeeId, "firstName", firstName, "lastName", lastName,
                "email", email, "password", password, "role", role, "department", department);
    }

    protected String validRegistrationJson() {
        return registrationJson("EMP-7001", "Anita", "Sharma",
                "anita.sharma@accessflow.local", PASSWORD, "EMPLOYEE", "Engineering");
    }

    protected Long idOf(String email) {
        return userService.getUserByEmail(email).getId();
    }

    /**
     * Files a pending request through the service, so tests start from the same
     * place a real applicant would rather than hand-building an entity.
     */
    protected Long createAccessRequest(Long applicantId, String application, String justification) {
        return accessRequestService
                .createRequest(new AccessRequestCreateRequest(application, justification), applicantId)
                .id();
    }

    /**
     * Drives a request all the way to APPROVED, through both stages.
     *
     * Phase 5 made resolution take two approvals - a MANAGER at stage 1 and an
     * IT_ADMIN at stage 2 - so a test that needs a resolved request has to resolve
     * it. This helper exists so those tests say what they mean ("a decided
     * request") instead of restating the chain, and so a test that cares about the
     * stages can do both approvals itself and assert on them.
     *
     * Uses the two seeded actors, which are never the applicant, so a test that
     * files the request as the employee is unaffected by the self-approval rule.
     */
    protected void approveEveryStage(Long requestId) {
        accessRequestService.approveRequest(requestId, idOf(MANAGER_EMAIL), "stage 1 ok");
        accessRequestService.approveRequest(requestId, idOf(IT_ADMIN_EMAIL), "stage 2 ok");
    }

    /**
     * The first stage only, leaving the request PENDING and awaiting stage 2.
     * The state a request is in between the two approvals.
     */
    protected void approveFirstStageOnly(Long requestId) {
        accessRequestService.approveRequest(requestId, idOf(MANAGER_EMAIL), "stage 1 ok");
    }

    protected String accessRequestJson(String application, String justification) {
        return json("application", application, "justification", justification);
    }

    /**
     * Signs a request in as one of the seeded actors, the way the browser chain
     * expects: a fully built principal with the real role authorities, taken from
     * the database rather than invented, so a test cannot pass with a role the
     * application would never grant.
     *
     * Meant for the Thymeleaf tests. The API tests authenticate with
     * httpBasic(...) because that is the API's transport.
     */
    protected RequestPostProcessor signedInAs(String email) {
        return user(new AccessFlowUserDetails(userService.getUserByEmail(email)));
    }
}
