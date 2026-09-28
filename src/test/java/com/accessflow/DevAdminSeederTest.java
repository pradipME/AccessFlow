package com.accessflow;

import com.accessflow.config.DevAdminSeedRunner;
import com.accessflow.entity.User;
import com.accessflow.repository.UserRepository;
import com.accessflow.service.DevAdminSeeder;
import com.accessflow.service.DevAdminSeeder.Outcome;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The local development administrator: what the seed does, and what it must not do.
 *
 * The account exists because a clean database has no SUPER_ADMIN and no way to
 * create one, which left the login page rejecting every credential. It is a
 * published development fixture, so these tests care about two opposite risks at
 * once - that the account is not created and nobody can sign in, and that it is
 * created in a way that leaks the password or quietly becomes a way around
 * authentication.
 *
 * Every test drives the real MySQL database and rolls back, and the sign-in tests
 * go through the real login chain and the real password encoder, so a seed that
 * stored a plaintext password or bypassed verification would fail here rather than
 * in a browser.
 */
@DisplayName("Local development - the seeded administrator")
@EnabledIfEnvironmentVariable(named = "ACCESSFLOW_DB_PASSWORD", matches = ".+")
class DevAdminSeederTest extends IntegrationTestSupport {

    private static final String DEV_EMAIL = DevAdminSeeder.EMAIL;
    private static final String DEV_PASSWORD = DevAdminSeeder.PASSWORD;
    private static final String DEV_EMPLOYEE_ID = DevAdminSeeder.EMPLOYEE_ID;

    @Autowired
    private DevAdminSeeder devAdminSeeder;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ApplicationContext applicationContext;

    /**
     * Removes the development administrator, inside the transaction that is about
     * to be rolled back.
     *
     * The rest of this project assumes each test starts from an empty table, which
     * holds until somebody starts the application with the {@code local} profile -
     * and that seeds this account for real, committed, into the same development
     * database the tests use. A test must not care whether that has happened yet,
     * so the precondition it needs is established here and the row is put back when
     * the transaction rolls back.
     */
    @BeforeEach
    void removeAdministratorSeededByALocalRun() {
        userRepository.findByEmail(DevAdminSeeder.EMAIL).ifPresent(userRepository::delete);
        userRepository.flush();
    }

    @Test
    @DisplayName("A database without the account gains it, with the documented details")
    void createsTheDevelopmentAdminWhenAbsent() {
        assertThat(userService.existsByEmail(DEV_EMAIL)).isFalse();

        assertThat(devAdminSeeder.seed()).isEqualTo(Outcome.CREATED);

        User admin = userService.getUserByEmail(DEV_EMAIL);
        assertThat(admin.getEmployeeId()).isEqualTo(DEV_EMPLOYEE_ID);
        assertThat(admin.getFirstName()).isEqualTo("AccessFlow");
        assertThat(admin.getLastName()).isEqualTo("Admin");
        assertThat(admin.isActive()).isTrue();
    }

    @Test
    @DisplayName("The seeded account is a SUPER_ADMIN, which is the only reason it is seeded")
    void theSeededAccountIsASuperAdmin() {
        devAdminSeeder.seed();

        assertThat(userService.getUserByEmail(DEV_EMAIL).getRole())
                .isEqualTo(User.Role.SUPER_ADMIN);
    }

    @Test
    @DisplayName("The password is stored as a BCrypt hash and the plaintext is nowhere in the column")
    void thePasswordIsStoredOnlyAsABcryptHash() {
        devAdminSeeder.seed();

        String stored = jdbcTemplate.queryForObject(
                "select password from users where email = ?", String.class, DEV_EMAIL);

        assertThat(passwordEncoder).isInstanceOf(BCryptPasswordEncoder.class);
        assertThat(stored).isNotNull().isNotEqualTo(DEV_PASSWORD);
        assertThat(stored).startsWith("$2");
        assertThat(passwordEncoder.matches(DEV_PASSWORD, stored)).isTrue();

        // Proved against the column rather than the entity, because the entity is a
        // mapped view of the row and the row is the thing the requirement is about.
        Integer plaintextRows = jdbcTemplate.queryForObject(
                "select count(*) from users where password = ?", Integer.class, DEV_PASSWORD);
        assertThat(plaintextRows).isZero();
    }

    @Test
    @DisplayName("The documented credentials sign in through the real login form")
    void theSeededAdminCanSignIn() throws Exception {
        devAdminSeeder.seed();

        MvcResult login = mockMvc.perform(post("/login")
                        .param("email", DEV_EMAIL)
                        .param("password", DEV_PASSWORD)
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"))
                .andReturn();

        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
        assertThat(session).isNotNull();

        // The landing page reads the role off the authentication, so this is the
        // application recognising the account as a SUPER_ADMIN rather than the test
        // asserting its own setup.
        mockMvc.perform(get("/").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString(DEV_EMAIL)))
                .andExpect(content().string(Matchers.containsString("SUPER_ADMIN")))
                .andExpect(content().string(Matchers.containsString("/requests/review")));
    }

    @Test
    @DisplayName("The same credentials work on the API, which is where SUPER_ADMIN access is visible")
    void theSeededAdminCanUseTheApi() throws Exception {
        devAdminSeeder.seed();

        // GET /api/users is a reviewer-only route, so this doubles as proof the
        // seeded role is the one the security configuration grants.
        mockMvc.perform(get("/api/users").with(httpBasic(DEV_EMAIL, DEV_PASSWORD)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Seeding twice creates no duplicate and reports the account as already present")
    void seedingTwiceDoesNotCreateADuplicate() {
        assertThat(devAdminSeeder.seed()).isEqualTo(Outcome.CREATED);
        Long firstId = userService.getUserByEmail(DEV_EMAIL).getId();

        assertThat(devAdminSeeder.seed()).isEqualTo(Outcome.ALREADY_PRESENT);

        assertThat(userService.getUserByEmail(DEV_EMAIL).getId()).isEqualTo(firstId);
        assertThat(usersWithDevEmail()).isOne();
    }

    @Test
    @DisplayName("An existing account holding the development email is not overwritten in any field")
    void anExistingAccountIsNotOverwritten() {
        Long existingId = createUser("EMP-9001", DEV_EMAIL, User.Role.EMPLOYEE);
        String existingHash = userRepository.findById(existingId).orElseThrow().getPassword();
        String existingFirstName = userService.getUserByEmail(DEV_EMAIL).getFirstName();

        assertThat(devAdminSeeder.seed()).isEqualTo(Outcome.ALREADY_PRESENT);

        User after = userService.getUserByEmail(DEV_EMAIL);
        assertThat(after.getId()).isEqualTo(existingId);
        assertThat(after.getPassword()).isEqualTo(existingHash);
        assertThat(after.getRole()).isEqualTo(User.Role.EMPLOYEE);
        assertThat(after.getEmployeeId()).isEqualTo("EMP-9001");
        assertThat(after.getFirstName()).isEqualTo(existingFirstName);
        // The documented password still does not open it, so the seed did not reset
        // the hash to the published one.
        assertThat(passwordEncoder.matches(DEV_PASSWORD, after.getPassword())).isFalse();
        assertThat(usersWithDevEmail()).isOne();
    }

    @Test
    @DisplayName("A different account already using the development employee id is reported, not clobbered")
    void aConflictingEmployeeIdIsReportedRatherThanClashing() {
        Long holderId = createUser(DEV_EMPLOYEE_ID, "other.owner@accessflow.local", User.Role.EMPLOYEE);

        assertThat(devAdminSeeder.seed()).isEqualTo(Outcome.EMPLOYEE_ID_TAKEN);

        assertThat(userService.existsByEmail(DEV_EMAIL)).isFalse();
        assertThat(userService.getUserByEmployeeId(DEV_EMPLOYEE_ID).getId()).isEqualTo(holderId);
    }

    @Test
    @DisplayName("A wrong password is still refused: the seed is not a way around verification")
    void aWrongPasswordIsStillRefused() throws Exception {
        devAdminSeeder.seed();

        mockMvc.perform(post("/login")
                        .param("email", DEV_EMAIL)
                        .param("password", "not-the-password")
                        .with(csrf()))
                .andExpect(redirectedUrl("/login?error"));

        mockMvc.perform(get("/api/users").with(httpBasic(DEV_EMAIL, "not-the-password")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("A deactivated seeded account is still locked out of both chains")
    void aDeactivatedSeededAccountCannotSignIn() throws Exception {
        devAdminSeeder.seed();
        User admin = userService.getUserByEmail(DEV_EMAIL);
        admin.setActive(false);
        userRepository.saveAndFlush(admin);

        mockMvc.perform(post("/login")
                        .param("email", DEV_EMAIL)
                        .param("password", DEV_PASSWORD)
                        .with(csrf()))
                .andExpect(redirectedUrl("/login?error"));

        mockMvc.perform(get("/api/users").with(httpBasic(DEV_EMAIL, DEV_PASSWORD)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Public registration cannot reuse the seeded account or mint another SUPER_ADMIN")
    void theSeededAccountIsNotAvailableThroughPublicRegistration() throws Exception {
        devAdminSeeder.seed();

        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registrationJson(DEV_EMPLOYEE_ID, "AccessFlow", "Admin",
                                DEV_EMAIL, DEV_PASSWORD, "SUPER_ADMIN", "Engineering")))
                .andExpect(status().isConflict());

        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registrationJson("DEV-SUPERADMIN-2", "Impostor", "Impostor",
                                "impostor@accessflow.local", PASSWORD, "SUPER_ADMIN", "Engineering")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("EMPLOYEE"));

        assertThat(usersWithDevEmail()).isOne();
    }

    @Test
    @DisplayName("The startup hook that seeds the account does not exist without the local profile")
    void theStartupHookIsAbsentWithoutTheLocalProfile() {
        // This context runs with no active profile, which is the production shape.
        // The bean is simply not registered, so a deployment started this way cannot
        // create the account however it is configured.
        assertThat(applicationContext.getBeanNamesForType(DevAdminSeedRunner.class)).isEmpty();
    }

    @Test
    @DisplayName("The startup hook does seed the account when it runs, as the local profile makes it do")
    void theStartupHookSeedsTheAccount() {
        // Driven directly rather than by booting the application under the local
        // profile: that would commit the row outside this test's transaction and
        // leave a committed account behind for every later test to trip over.
        new DevAdminSeedRunner(devAdminSeeder).run();

        assertThat(userService.getUserByEmail(DEV_EMAIL).getRole())
                .isEqualTo(User.Role.SUPER_ADMIN);
    }

    /**
     * Counted in SQL rather than through getAllUsers, so the assertion is about the
     * rows the unique constraints are there to protect, not about a list size.
     */
    private int usersWithDevEmail() {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from users where email = ?", Integer.class, DEV_EMAIL);
        return count == null ? 0 : count;
    }
}
