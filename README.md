# AccessFlow

**IT Access Request & Approval System**

An internal company system where employees request access to applications
(GitHub, Jira, AWS, VPN, HRMS, ...) through a defined approval workflow instead
of email or ad-hoc IT tickets.

## Current Status

**Phase 0 - Project Foundation: complete**

| Phase | Scope | Status |
|---|---|---|
| Phase 0 | Maven project, Spring Boot bootstrap, configuration, Git | Done |
| Phase 1 | Project architecture, `application.properties`, MySQL setup | Not started |
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

Deliberately minimal for Phase 0:

- `spring-boot-starter-web` - embedded Tomcat + Spring MVC, required to run the app
- `spring-boot-starter-test` (test scope) - JUnit 5, Mockito, Spring Test

Security, JPA/Hibernate, MySQL driver and Thymeleaf are intentionally **not**
present yet. They get added when a module actually needs them.

## Project Layout

```
AccessFlow
├── pom.xml
├── README.md
├── .gitignore
└── src
    ├── main
    │   ├── java/com/accessflow
    │   │   └── AccessFlowApplication.java
    │   └── resources
    │       └── application.properties
    └── test
        └── java/com/accessflow
```

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
