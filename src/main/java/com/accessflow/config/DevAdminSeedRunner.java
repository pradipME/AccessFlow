package com.accessflow.config;

import com.accessflow.service.DevAdminSeeder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Seeds the local development administrator at startup, and only when the
 * application is started with the {@code local} profile.
 *
 * <p>The profile is the whole of the guard, and it is meant to be the only thing
 * standing between a documented known password and a production SUPER_ADMIN. That
 * is why the gate is a profile rather than a property default: a deployment would
 * have to be started with {@code --spring.profiles.active=local} to run this, which
 * is a deliberate, visible act, and the bean simply does not exist otherwise. No
 * profile other than {@code local} enables it, and no profile file in this project
 * changes anything else, so activating it on a laptop affects only this runner.
 *
 * <p>Wired through {@link DevAdminSeeder} rather than duplicating the account
 * details, so the seeder and its tests share one definition. Nothing here logs the
 * password.
 */
@Component
@Profile("local")
public class DevAdminSeedRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DevAdminSeedRunner.class);

    private final DevAdminSeeder devAdminSeeder;

    public DevAdminSeedRunner(DevAdminSeeder devAdminSeeder) {
        this.devAdminSeeder = devAdminSeeder;
    }

    @Override
    public void run(String... args) {
        DevAdminSeeder.Outcome outcome = devAdminSeeder.seed();

        switch (outcome) {
            case CREATED -> log.warn(
                    "LOCAL DEVELOPMENT ONLY: seeded the development administrator {} ({}) with a known "
                            + "password. It is not a production credential - change or delete it before "
                            + "any production deployment.",
                    DevAdminSeeder.EMAIL, DevAdminSeeder.EMPLOYEE_ID);
            case ALREADY_PRESENT -> log.info(
                    "Development administrator {} already exists; left unchanged.", DevAdminSeeder.EMAIL);
            case EMPLOYEE_ID_TAKEN -> log.warn(
                    "Not seeding the development administrator: employee id {} is already used by another "
                            + "account. That account was not modified.",
                    DevAdminSeeder.EMPLOYEE_ID);
        }
    }
}
