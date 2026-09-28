package com.accessflow;

import com.accessflow.config.DevEmployeeSeedRunner;
import com.accessflow.entity.User;
import com.accessflow.repository.UserRepository;
import com.accessflow.service.DevEmployeeSeeder;
import com.accessflow.service.DevEmployeeSeeder.DemoEmployee;
import com.accessflow.service.DevEmployeeSeeder.SeedReport;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The fifty demo employees: what the demo seed does, and the permissions it must
 * not hand out.
 *
 * The roster exists so a demonstration has a room full of employees rather than one
 * administrator. That makes it a security test as much as a data test: a demo
 * account that could reach the review queue would make the demo show the wrong
 * thing, so the reviewer-only surfaces are asserted to stay closed to an employee.
 *
 * Every test drives the real MySQL database and rolls back, and the sign-in tests
 * go through the real login chain and the real password encoder, so a seed that
 * stored a plaintext password would fail here rather than in a browser.
 */
@DisplayName("Local development - the demo employees")
@EnabledIfEnvironmentVariable(named = "ACCESSFLOW_DB_PASSWORD", matches = ".+")
class DevEmployeeSeederTest extends IntegrationTestSupport {

    private static final String DEMO_PASSWORD = DevEmployeeSeeder.PASSWORD;

    @Autowired
    private DevEmployeeSeeder devEmployeeSeeder;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ApplicationContext applicationContext;

    /**
     * Removes any demo employee a local run has already committed, inside the
     * transaction that is about to be rolled back.
     *
     * This project's other tests assume an empty table, which holds until somebody
     * starts the application with the {@code local} profile - that seeds the roster
     * for real into the same development database the tests use. These tests assert
     * on counts such as "fifty created", so they have to control their starting
     * state rather than inherit it, and the rows come back on rollback.
     */
    @BeforeEach
    void removeRosterSeededByALocalRun() {
        for (DemoEmployee demo : DevEmployeeSeeder.DEMO_EMPLOYEES) {
            userRepository.findByEmail(demo.email()).ifPresent(userRepository::delete);
        }
        userRepository.flush();
    }

    @Test
    @DisplayName("The roster is exactly fifty employees numbered EMP-1001 to EMP-1050")
    void theRosterIsFiftyEmployeesInTheDocumentedIdRange() {
        SeedReport report = devEmployeeSeeder.seed();

        assertThat(report.created()).isEqualTo(50);

        List<String> expectedIds = IntStream
                .rangeClosed(DevEmployeeSeeder.FIRST_EMPLOYEE_NUMBER,
                        DevEmployeeSeeder.FIRST_EMPLOYEE_NUMBER + DevEmployeeSeeder.EMPLOYEE_COUNT - 1)
                .mapToObj(number -> DevEmployeeSeeder.EMPLOYEE_ID_PREFIX + number)
                .toList();

        assertThat(seededEmployeeIds()).containsExactlyInAnyOrderElementsOf(expectedIds);
        assertThat(seededEmployeeIds()).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("Every seeded account is an active EMPLOYEE, and none of them is a reviewer")
    void everySeededAccountIsAnActiveEmployee() {
        devEmployeeSeeder.seed();

        List<User> employees = seededEmployees();

        assertThat(employees).hasSize(50);
        assertThat(employees).allSatisfy(user -> {
            assertThat(user.getRole()).isEqualTo(User.Role.EMPLOYEE);
            assertThat(user.isActive()).isTrue();
            assertThat(user.getDepartment()).isEqualTo(DevEmployeeSeeder.DEPARTMENT);
        });
    }

    @Test
    @DisplayName("Emails follow the firstname.lastname convention and are all distinct")
    void emailsFollowTheDocumentedConvention() {
        devEmployeeSeeder.seed();

        Set<String> emails = seededEmployees().stream()
                .map(User::getEmail)
                .collect(Collectors.toSet());

        assertThat(emails).hasSize(50);
        for (DemoEmployee demo : DevEmployeeSeeder.DEMO_EMPLOYEES) {
            assertThat(userService.existsByEmail(demo.email()))
                    .as("%s must be seeded as %s", demo.employeeId(), demo.email())
                    .isTrue();
        }
        // None of them may collide with the development administrator.
        assertThat(emails).doesNotContain("admin@" + DevEmployeeSeeder.EMAIL_DOMAIN);
    }

    @Test
    @DisplayName("The demo password is stored as a BCrypt hash and the plaintext is nowhere in the table")
    void theDemoPasswordIsStoredOnlyAsABcryptHash() {
        devEmployeeSeeder.seed();

        String stored = jdbcTemplate.queryForObject(
                "select password from users where employee_id = ?", String.class, "EMP-1001");

        assertThat(passwordEncoder).isInstanceOf(BCryptPasswordEncoder.class);
        assertThat(stored).isNotNull().isNotEqualTo(DEMO_PASSWORD);
        assertThat(stored).startsWith("$2");
        assertThat(passwordEncoder.matches(DEMO_PASSWORD, stored)).isTrue();

        // Counted in SQL, because the requirement is about what is in the column.
        Integer plaintextRows = jdbcTemplate.queryForObject(
                "select count(*) from users where password = ?", Integer.class, DEMO_PASSWORD);
        assertThat(plaintextRows).isZero();

        // All fifty share the one password, and every one of them is hashed with a
        // distinct salt rather than one hash copied fifty times.
        List<String> hashes = jdbcTemplate.queryForList(
                "select password from users where employee_id like ?", String.class,
                DevEmployeeSeeder.EMPLOYEE_ID_PREFIX + "%");
        assertThat(hashes).hasSize(50).doesNotHaveDuplicates();
        assertThat(hashes).allSatisfy(hash -> assertThat(passwordEncoder.matches(DEMO_PASSWORD, hash)).isTrue());
    }

    @Test
    @DisplayName("Seeding again creates no duplicate and changes no existing employee")
    void seedingTwiceCreatesNoDuplicates() {
        devEmployeeSeeder.seed();
        List<String> idsBefore = seededEmployeeIds();
        String hashBefore = userService.getUserByEmployeeId("EMP-1001").getPassword();

        SeedReport second = devEmployeeSeeder.seed();

        assertThat(second.created()).isZero();
        assertThat(second.alreadyPresent()).isEqualTo(50);
        assertThat(second.employeeIdTaken()).isZero();
        assertThat(seededEmployeeIds()).isEqualTo(idsBefore);
        assertThat(userService.getUserByEmployeeId("EMP-1001").getPassword()).isEqualTo(hashBefore);
    }

    @Test
    @DisplayName("An employee that already exists is not overwritten in any field")
    void anExistingEmployeeIsNotOverwritten() {
        // Someone already registered the address the demo roster derives for
        // Rahul Sharma, under their own name and password. The roster must leave it
        // alone rather than "correcting" it to look like the demo.
        Long existingId = createUser("EMP-1001", "rahul.sharma@" + DevEmployeeSeeder.EMAIL_DOMAIN,
                User.Role.EMPLOYEE);
        String existingHash = userRepository.findById(existingId).orElseThrow().getPassword();
        String existingFirstName = userService
                .getUserByEmail("rahul.sharma@" + DevEmployeeSeeder.EMAIL_DOMAIN).getFirstName();

        SeedReport report = devEmployeeSeeder.seed();

        assertThat(report.created()).isEqualTo(49);
        assertThat(report.alreadyPresent()).isOne();
        assertThat(report.employeeIdTaken()).isZero();

        User after = userService.getUserByEmail("rahul.sharma@" + DevEmployeeSeeder.EMAIL_DOMAIN);
        assertThat(after.getId()).isEqualTo(existingId);
        assertThat(after.getPassword()).isEqualTo(existingHash);
        assertThat(after.getFirstName()).isEqualTo(existingFirstName);
        assertThat(after.getLastName()).isEqualTo("Sharma");
        assertThat(after.getEmployeeId()).isEqualTo("EMP-1001");
        assertThat(passwordEncoder.matches(DEMO_PASSWORD, after.getPassword())).isFalse();
    }

    @Test
    @DisplayName("A demo employee id already held by somebody else is skipped, and that account is left alone")
    void aConflictingEmployeeIdIsSkippedRatherThanClashing() {
        Long holderId = createUser("EMP-1050", "someone.else@accessflow.local", User.Role.MANAGER);

        SeedReport report = devEmployeeSeeder.seed();

        assertThat(report.employeeIdTaken()).isOne();
        assertThat(report.created()).isEqualTo(49);
        assertThat(userService.getUserByEmployeeId("EMP-1050").getId()).isEqualTo(holderId);
        assertThat(userService.existsByEmail("ashwin.patel@" + DevEmployeeSeeder.EMAIL_DOMAIN)).isFalse();
    }

    @Test
    @DisplayName("A demo employee signs in through the browser UI and is shown as an employee")
    void aDemoEmployeeCanSignIn() throws Exception {
        devEmployeeSeeder.seed();

        MvcResult login = mockMvc.perform(post("/login")
                        .param("email", "rahul.sharma@" + DevEmployeeSeeder.EMAIL_DOMAIN)
                        .param("password", DEMO_PASSWORD)
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"))
                .andReturn();

        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
        assertThat(session).isNotNull();

        mockMvc.perform(get("/").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("Rahul Sharma")))
                .andExpect(content().string(Matchers.containsString("EMPLOYEE")))
                .andExpect(content().string(Matchers.containsString(DevEmployeeSeeder.DEPARTMENT)));
    }

    @Test
    @DisplayName("A demo employee is refused the review queue, in the UI and on the API")
    void aDemoEmployeeCannotReachReviewerOnlyFunctionality() throws Exception {
        devEmployeeSeeder.seed();
        String email = "rahul.sharma@" + DevEmployeeSeeder.EMAIL_DOMAIN;

        MvcResult login = mockMvc.perform(post("/login")
                        .param("email", email)
                        .param("password", DEMO_PASSWORD)
                        .with(csrf()))
                .andExpect(redirectedUrl("/"))
                .andReturn();
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);

        // 403 rather than a redirect to the sign-in form: the employee is
        // authenticated and is being refused, which is the distinction that matters.
        mockMvc.perform(get("/requests/review").session(session))
                .andExpect(status().isForbidden())
                .andExpect(content().string(Matchers.containsString("do not have permission")));

        // And the reviewer's own pages stay out of the employee's navigation.
        mockMvc.perform(get("/").session(session))
                .andExpect(content().string(Matchers.not(Matchers.containsString("/requests/review"))));

        mockMvc.perform(get("/api/access-requests").with(httpBasic(email, DEMO_PASSWORD)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/users").with(httpBasic(email, DEMO_PASSWORD)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("A demo employee cannot approve, so the two-stage workflow still needs a reviewer")
    void aDemoEmployeeHoldsNoApprovalRights() throws Exception {
        devEmployeeSeeder.seed();
        String email = "neha.deshmukh@" + DevEmployeeSeeder.EMAIL_DOMAIN;
        Long requestId = createAccessRequest(idOf(email), "GitHub", "demo request");

        mockMvc.perform(patch("/api/access-requests/" + requestId + "/approve")
                        .with(httpBasic(email, DEMO_PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("notes", "approving my own request")))
                .andExpect(status().isForbidden());

        assertThat(accessRequestService.getAllRequests()).hasSize(1);
    }

    @Test
    @DisplayName("The startup hook for the roster does not exist without the local profile")
    void theStartupHookIsAbsentWithoutTheLocalProfile() {
        assertThat(applicationContext.getBeanNamesForType(DevEmployeeSeedRunner.class)).isEmpty();
    }

    @Test
    @DisplayName("The startup hook seeds the roster when it runs, as the local profile makes it do")
    void theStartupHookSeedsTheRoster() {
        // Driven directly rather than by booting the application under the local
        // profile: that would commit fifty rows outside this test's transaction.
        new DevEmployeeSeedRunner(devEmployeeSeeder).run();

        assertThat(seededEmployees()).hasSize(50);
    }

    /**
     * Only the seeded demo employees, so the four standard actors this harness
     * creates in every test cannot be mistaken for part of the roster.
     */
    private List<User> seededEmployees() {
        return userRepository.findAll().stream()
                .filter(user -> user.getEmployeeId() != null
                        && user.getEmployeeId().startsWith(DevEmployeeSeeder.EMPLOYEE_ID_PREFIX))
                .toList();
    }

    private List<String> seededEmployeeIds() {
        return seededEmployees().stream()
                .map(User::getEmployeeId)
                .sorted()
                .toList();
    }
}
