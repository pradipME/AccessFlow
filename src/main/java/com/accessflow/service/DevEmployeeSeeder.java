package com.accessflow.service;

import java.util.List;
import java.util.Locale;

import com.accessflow.entity.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates the demo employees used for a college demonstration, and nothing else.
 *
 * <p>AccessFlow Technologies Pvt. Ltd. is demonstrated with a room full of people
 * rather than one administrator, and a single SUPER_ADMIN is not enough for that:
 * the workflow is only interesting once several employees are filing and tracking
 * requests, and a demo where every login is the same person never shows it. So
 * this seeds fifty ordinary employees with realistic Indian names, drawn from
 * different regions so the class looks like a real organisation rather than a
 * list of placeholders.
 *
 * <p>They are deliberately plain employees. Nothing here is a reviewer, so a demo
 * shows the permission model doing its job: an employee can file and track a
 * request and is refused the review queue, and the accounts that can approve are
 * still only the ones a real deployment would grant that role to.
 *
 * <p>The names are held in code rather than in a CSV or a SQL script, for the same
 * reason the administrator is not in {@code db/setup.sql}: a script cannot produce
 * a BCrypt hash, and inserting plaintext to be hashed later would put the demo
 * password in a file that is not a development mechanism. The employee id and the
 * email are both derived from the entry rather than written out, so the range
 * EMP-1001..EMP-1050 and the {@code firstname.lastname} convention cannot drift
 * out of step with a typo.
 *
 * <p>Like {@link DevAdminSeeder}, this is ordinary injectable logic with no profile
 * of its own, and the account it creates is reached through the ordinary
 * {@code CustomUserDetailsService}. Seeding is idempotent: an employee that already
 * exists is left exactly as it is, including its password, and the report says how
 * many were skipped.
 */
@Service
public class DevEmployeeSeeder {

    /**
     * One published password for all fifty demo employees, so a demonstrator can
     * type the same thing fifty times without a notepad. It is a demo fixture, not
     * a secret, and it is not a production credential.
     */
    public static final String PASSWORD = "Employee@123";

    /**
     * Set on every account so the pages that show a department have something
     * truthful to show. The demo is a single IT department asking for access.
     */
    public static final String DEPARTMENT = "Information Technology";

    public static final String EMPLOYEE_ID_PREFIX = "EMP-";
    public static final int FIRST_EMPLOYEE_NUMBER = 1001;
    public static final int EMPLOYEE_COUNT = 50;
    public static final String EMAIL_DOMAIN = "accessflow.local";

    /**
     * One demo employee. The number is the last three digits of the employee id, so
     * the id and the id range are defined by the list order rather than repeated in
     * fifty literals.
     */
    public record DemoEmployee(int number, String firstName, String lastName) {

        public String employeeId() {
            return EMPLOYEE_ID_PREFIX + number;
        }

        /**
         * The documented sign-in convention: firstname.lastname@accessflow.local.
         * Lower-cased so the address is the same in an email client, in the README
         * and in the database.
         */
        public String email() {
            return (firstName + "." + lastName).toLowerCase(Locale.ROOT) + "@" + EMAIL_DOMAIN;
        }
    }

    /**
     * The demo roster. First names are unique, which is what guarantees fifty
     * distinct email addresses; surnames repeat because they do in a real
     * organisation.
     */
    public static final List<DemoEmployee> DEMO_EMPLOYEES = List.of(
            new DemoEmployee(1001, "Rahul", "Sharma"),
            new DemoEmployee(1002, "Amit", "Patil"),
            new DemoEmployee(1003, "Neha", "Deshmukh"),
            new DemoEmployee(1004, "Priya", "Kulkarni"),
            new DemoEmployee(1005, "Rohan", "Joshi"),
            new DemoEmployee(1006, "Sneha", "Pawar"),
            new DemoEmployee(1007, "Vikram", "Verma"),
            new DemoEmployee(1008, "Anjali", "Nair"),
            new DemoEmployee(1009, "Arjun", "Reddy"),
            new DemoEmployee(1010, "Kavita", "Iyer"),
            new DemoEmployee(1011, "Rajesh", "Rao"),
            new DemoEmployee(1012, "Meera", "Menon"),
            new DemoEmployee(1013, "Sandeep", "Singh"),
            new DemoEmployee(1014, "Divya", "Patel"),
            new DemoEmployee(1015, "Manoj", "Yadav"),
            new DemoEmployee(1016, "Pooja", "Banerjee"),
            new DemoEmployee(1017, "Karthik", "Pillai"),
            new DemoEmployee(1018, "Anusha", "Desai"),
            new DemoEmployee(1019, "Anil", "Chatterjee"),
            new DemoEmployee(1020, "Radhika", "Ghosh"),
            new DemoEmployee(1021, "Sarika", "Mishra"),
            new DemoEmployee(1022, "Geeta", "Saxena"),
            new DemoEmployee(1023, "Sunil", "Trivedi"),
            new DemoEmployee(1024, "Latha", "Iyer"),
            new DemoEmployee(1025, "Bhavana", "Menon"),
            new DemoEmployee(1026, "Deepak", "Sharma"),
            new DemoEmployee(1027, "Sunita", "Verma"),
            new DemoEmployee(1028, "Ravi", "Patel"),
            new DemoEmployee(1029, "Kiran", "Nair"),
            new DemoEmployee(1030, "Nisha", "Reddy"),
            new DemoEmployee(1031, "Sudhir", "Rao"),
            new DemoEmployee(1032, "Shreya", "Deshmukh"),
            new DemoEmployee(1033, "Yogesh", "Joshi"),
            new DemoEmployee(1034, "Preeti", "Kulkarni"),
            new DemoEmployee(1035, "Harish", "Iyer"),
            new DemoEmployee(1036, "Gaurav", "Menon"),
            new DemoEmployee(1037, "Swati", "Banerjee"),
            new DemoEmployee(1038, "Manish", "Chatterjee"),
            new DemoEmployee(1039, "Vandana", "Das"),
            new DemoEmployee(1040, "Praveen", "Ghosh"),
            new DemoEmployee(1041, "Rekha", "Mishra"),
            new DemoEmployee(1042, "Archana", "Saxena"),
            new DemoEmployee(1043, "Aditya", "Trivedi"),
            new DemoEmployee(1044, "Deepa", "Yadav"),
            new DemoEmployee(1045, "Nikhil", "Pillai"),
            new DemoEmployee(1046, "Ritika", "Desai"),
            new DemoEmployee(1047, "Sanjay", "Singh"),
            new DemoEmployee(1048, "Aishwarya", "Banerjee"),
            new DemoEmployee(1049, "Varun", "Pawar"),
            new DemoEmployee(1050, "Ashwin", "Patel"));

    /**
     * What a call to {@link #seed()} did. A count rather than a single outcome,
     * because a partial run is normal and worth being able to say out loud: the
     * administrator is one account, this is fifty.
     */
    public record SeedReport(int created, int alreadyPresent, int employeeIdTaken) {

        public int skipped() {
            return alreadyPresent + employeeIdTaken;
        }
    }

    private final UserService userService;

    public DevEmployeeSeeder(UserService userService) {
        this.userService = userService;
    }

    /**
     * Creates every demo employee that does not exist yet, in one transaction.
     *
     * <p>Each employee is checked by email first and by employee id second, because
     * those are the two unique keys and either one belonging to somebody else is a
     * reason to leave the row alone rather than a reason to fail. All fifty share
     * one transaction, so a run either completes or leaves the database as it found
     * it; the unique constraints remain the real backstop, exactly as for every
     * other user creation.
     */
    @Transactional
    public SeedReport seed() {
        int created = 0;
        int alreadyPresent = 0;
        int employeeIdTaken = 0;

        for (DemoEmployee demo : DEMO_EMPLOYEES) {
            if (userService.existsByEmail(demo.email())) {
                alreadyPresent++;
                continue;
            }
            if (userService.existsByEmployeeId(demo.employeeId())) {
                employeeIdTaken++;
                continue;
            }

            User employee = new User();
            employee.setEmployeeId(demo.employeeId());
            employee.setFirstName(demo.firstName());
            employee.setLastName(demo.lastName());
            employee.setEmail(demo.email());
            employee.setPassword(PASSWORD);
            employee.setRole(User.Role.EMPLOYEE);
            employee.setDepartment(DEPARTMENT);
            employee.setActive(true);

            // createUser re-checks both unique keys and BCrypt-hashes the password
            // before saving, so no code path here can persist the demo plaintext.
            userService.createUser(employee);
            created++;
        }

        return new SeedReport(created, alreadyPresent, employeeIdTaken);
    }
}
