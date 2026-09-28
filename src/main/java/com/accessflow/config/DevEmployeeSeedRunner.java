package com.accessflow.config;

import com.accessflow.service.DevEmployeeSeeder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Seeds the demo employees at startup, and only when the application is started
 * with the {@code local} profile.
 *
 * <p>The same gate as {@link DevAdminSeedRunner}, and for the same reason: a
 * published password that mints fifty accounts must never be reachable in a real
 * deployment. Without the profile this bean does not exist, so nothing here can
 * run however the application is configured. A separate runner rather than a
 * second job inside the administrator's one, so each logs its own outcome and
 * neither can report the other's work as its own.
 *
 * <p>Nothing here logs the demo password. It is in the README, which is where a
 * demonstrator should be looking for it.
 */
@Component
@Profile("local")
public class DevEmployeeSeedRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DevEmployeeSeedRunner.class);

    private final DevEmployeeSeeder devEmployeeSeeder;

    public DevEmployeeSeedRunner(DevEmployeeSeeder devEmployeeSeeder) {
        this.devEmployeeSeeder = devEmployeeSeeder;
    }

    @Override
    public void run(String... args) {
        DevEmployeeSeeder.SeedReport report = devEmployeeSeeder.seed();

        if (report.created() > 0) {
            log.warn("LOCAL DEVELOPMENT / COLLEGE DEMO ONLY: seeded {} demo employee accounts "
                            + "({}..{}, sign in as firstname.lastname@{} with the demo password in the "
                            + "README). They are not production credentials.",
                    report.created(),
                    DevEmployeeSeeder.EMPLOYEE_ID_PREFIX + DevEmployeeSeeder.FIRST_EMPLOYEE_NUMBER,
                    DevEmployeeSeeder.EMPLOYEE_ID_PREFIX
                            + (DevEmployeeSeeder.FIRST_EMPLOYEE_NUMBER + DevEmployeeSeeder.EMPLOYEE_COUNT - 1),
                    DevEmployeeSeeder.EMAIL_DOMAIN);
        } else {
            log.info("All {} demo employees already exist; none were changed.",
                    DevEmployeeSeeder.EMPLOYEE_COUNT);
        }

        if (report.employeeIdTaken() > 0) {
            log.warn("Skipped {} demo employees because their employee id is already used by another "
                    + "account. Those accounts were not modified.", report.employeeIdTaken());
        }
    }
}
