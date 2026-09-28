package com.accessflow.service;

import com.accessflow.entity.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates the single known local development administrator, and nothing else.
 *
 * <p>A fresh database has no way to reach SUPER_ADMIN. Registration is public but
 * {@code UserController} forces every self-registered account to EMPLOYEE, and the
 * only other way to set the role is to already be a SUPER_ADMIN. So on a clean
 * local database the browser UI is unreachable: the login page is served, every
 * credential is rejected, and there is no account to sign in with. This class is
 * that missing first account, for local development only.
 *
 * <p>The password is a published development fixture, deliberately not a secret.
 * It is stored as a BCrypt hash and never in plaintext: the only write path used
 * here is {@link UserService#createUser}, which hashes before saving, so the
 * plaintext constant cannot reach the database even by accident. The only place it
 * exists as plaintext is this constant and the README.
 *
 * <p>Nothing here weakens authentication. The account is an ordinary user row
 * reached through the ordinary {@code CustomUserDetailsService}, so a wrong
 * password is still rejected, a deactivated account is still refused, and the
 * self-approval rule still applies to it like any other SUPER_ADMIN.
 *
 * <p>Re-running is the normal case rather than an error, so {@link #seed()} is
 * safe to call on every startup: an existing account is left completely alone,
 * including its password, role and every other field. That is why this holds no
 * state and returns an {@link Outcome} instead of throwing.
 *
 * <p>The class itself is not restricted to a profile. It is ordinary injectable
 * logic, which is what lets the tests drive it directly. What keeps it out of a
 * production deployment is {@code DevAdminSeedRunner}, the only caller, which
 * exists solely under the {@code local} profile.
 */
@Service
public class DevAdminSeeder {

    public static final String EMAIL = "admin@accessflow.local";
    public static final String EMPLOYEE_ID = "DEV-SUPERADMIN";
    public static final String FIRST_NAME = "AccessFlow";
    public static final String LAST_NAME = "Admin";

    /**
     * A local development password, published on purpose. It is not a production
     * credential and must never be treated as one: see the README section on the
     * local development login.
     */
    public static final String PASSWORD = "AccessFlow@123";

    /**
     * What a call to {@link #seed()} did.
     */
    public enum Outcome {

        /** The development administrator did not exist and has just been created. */
        CREATED,

        /** An account already uses the development email, and was left untouched. */
        ALREADY_PRESENT,

        /**
         * A different account already uses the development employee id. The
         * employee id is unique, so seeding would fail; the conflicting account is
         * reported rather than altered.
         */
        EMPLOYEE_ID_TAKEN
    }

    private final UserService userService;

    public DevAdminSeeder(UserService userService) {
        this.userService = userService;
    }

    /**
     * Creates the development administrator if, and only if, it is absent.
     *
     * <p>Transactional so the existence check and the insert are one unit: two
     * applications starting together cannot both see an empty table and both
     * insert. The unique constraints on email and employee_id remain the real
     * backstop, exactly as they are for every other user creation.
     */
    @Transactional
    public Outcome seed() {
        if (userService.existsByEmail(EMAIL)) {
            return Outcome.ALREADY_PRESENT;
        }
        if (userService.existsByEmployeeId(EMPLOYEE_ID)) {
            return Outcome.EMPLOYEE_ID_TAKEN;
        }

        User admin = new User();
        admin.setEmployeeId(EMPLOYEE_ID);
        admin.setFirstName(FIRST_NAME);
        admin.setLastName(LAST_NAME);
        admin.setEmail(EMAIL);
        admin.setPassword(PASSWORD);
        admin.setRole(User.Role.SUPER_ADMIN);
        admin.setActive(true);

        // createUser re-checks both unique keys and BCrypt-hashes the password
        // before saving, so no code path here can persist the plaintext constant.
        userService.createUser(admin);

        return Outcome.CREATED;
    }
}
