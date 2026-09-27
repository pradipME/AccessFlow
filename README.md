# AccessFlow

**IT Access Request & Approval System**

An internal company system where employees request access to applications
(GitHub, Jira, AWS, VPN, HRMS, ...) through a defined approval workflow instead
of email or ad-hoc IT tickets.

## Current Status

**Phase 1A - MySQL Foundation: configured, pending database creation**

| Phase | Scope | Status |
|---|---|---|
| Phase 0 | Maven project, Spring Boot bootstrap, configuration, Git | Done |
| Phase 1A | MySQL connectivity, DataSource configuration, credentials setup | Configured |
| Phase 1B | JPA / Hibernate, first entities, repositories | Not started |
| Phase 2+ | Domain modules (User, Role, Access Request, workflow, security) | Not started |

## Technology Stack

| Layer | Technology |
|---|---|
| Language | Java 21 (Temurin) |
| Framework | Spring Boot 3.5.4 |
| Build tool | Maven 3.9.16 |
| Packaging | JAR |
| IDE | Eclipse IDE (m2e) |
| Database | MySQL (introduced in Phase 1) |
| Version control | Git |
| Frontend | Thymeleaf (introduced later) |

## Current Dependencies

Deliberately minimal. Nothing is added before a module needs it.

| Dependency | Scope | Why |
|---|---|---|
| `spring-boot-starter-web` | compile | Embedded Tomcat + Spring MVC. Required to run the app at all. |
| `spring-boot-starter-jdbc` | compile | Brings `spring-jdbc` + HikariCP. Required for `DataSource` auto-configuration. |
| `mysql-connector-j` | compile | The MySQL JDBC driver itself. Version managed by the Spring Boot parent. |
| `spring-boot-starter-test` | test | JUnit 5, Mockito, Spring Test. |

No versions are declared: the `spring-boot-starter-parent` pins all of them
(Spring Boot 3.5.4 -> Connector/J 9.3.0).

Security, JPA/Hibernate and Thymeleaf are intentionally **not** present yet.

## Project Layout

```
AccessFlow
├── pom.xml
├── README.md
├── .gitignore
├── db/
│   └── setup.sql              create database + dedicated app user
└── src
    ├── main
    │   ├── java/com/accessflow
    │   │   └── AccessFlowApplication.java
    │   └── resources
    │       └── application.properties   committed, contains no secrets
    └── test
        └── java/com/accessflow
            └── AccessFlowDatabaseConnectionTest.java
```

## Database credentials

**No password is ever stored in this repository.**

`application.properties` reads credentials from environment variables and
falls back to safe local defaults, so the same file works on a laptop, in CI
and in production without edits.

| Variable | Default | Required |
|---|---|---|
| `ACCESSFLOW_DB_HOST` | `localhost` | no |
| `ACCESSFLOW_DB_PORT` | `3306` | no |
| `ACCESSFLOW_DB_NAME` | `accessflow` | no |
| `ACCESSFLOW_DB_USER` | `accessflow` | no |
| `ACCESSFLOW_DB_PASSWORD` | *(empty)* | **yes** |

### One-time setup (Windows)

1. Create the database and application user by running `db/setup.sql`
   in MySQL Workbench as `root` (replace the placeholder password first).
2. Set the password in your shell, this session only:

   ```powershell
   $env:ACCESSFLOW_DB_PASSWORD = "your-password"
   ```

3. Run the connection test:

   ```powershell
   mvn test -Dtest=AccessFlowDatabaseConnectionTest
   ```

   Without the variable the test is **skipped** and the build still passes,
   so a fresh clone builds cleanly on a machine with no database.

### Making it permanent

For real development you want the variable available every time:

- **System-wide:** `SystemPropertiesAdvanced` -> `Environment Variables` -> add
  `ACCESSFLOW_DB_PASSWORD`. Requires a new terminal, and it is readable by
  every user on the machine.
- **Per-project (preferred for a laptop):** Eclipse -> `Run Configurations` ->
  select the run config -> `Environment` tab -> add the variable. It is stored
  in the workspace, not in the repository.
- **Per-shell:** the `$env:` form above, in the PowerShell profile.

### Fail-fast alternative

`application.properties` currently uses `${ACCESSFLOW_DB_PASSWORD:}` - an empty
default - so the application boots even when the credential is missing, and the
failure appears at the point a connection is first used. For production you
would instead remove the `:` and the trailing empty value, turning it into
`${ACCESSFLOW_DB_PASSWORD}`. Spring Boot then refuses to start when the secret
is absent, which is the safer behaviour for a deployed system.

## Build & Run

```bash
# compile and run tests
mvn clean test

# start the application (http://localhost:8080)
mvn spring-boot:run

# build an executable jar
mvn clean package
java -jar target/accessflow-0.0.1-SNAPSHOT.jar
```

## Main Class

`com.accessflow.AccessFlowApplication`
