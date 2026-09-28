# AccessFlow

**IT Access Request & Approval System**

An internal company system where employees request access to applications
(GitHub, Jira, AWS, VPN, HRMS, ...) through a defined approval workflow instead
of email or ad-hoc IT tickets.

## Current Status

**Phases 0-5 implemented. Phase 6 - audit, documentation and release checks: complete.**

| Phase | Scope | Status |
|---|---|---|
| Phase 0 | Maven project, Spring Boot bootstrap, configuration, Git | Done |
| Phase 1A | MySQL connectivity, DataSource configuration, credentials setup | Configured |
| Phase 1B | JPA / Hibernate, User entity, user API, authentication | Done |
| Phase 2 | Access Request entity, approval workflow, role-based review | Done |
| Phase 3 | Thymeleaf UI over the same workflow | Done |
| Phase 4 | Access governance: applicant withdrawal, request history, paginated review queue | Done |
| Phase 5 | Multi-approver: two sequential stages, both required | Done |
| Phase 6 | Production-readiness audit, schema and backfill documentation, release checks | Done |
| Later | application catalogue, more than two stages, reporting-line routing | Not started |

Phase 6 adds no feature. What it changed is the documentation and a small number
of tests:

- The schema section is the schema as the live database actually holds it,
  including the two things that were wrong in the previous draft: the foreign
  keys do not have the names that were shown, and the two `status` columns store
  their values in a different order than the Java enums declare.
- A request filed before the stages existed is now a documented, tested state
  rather than an accident, and the backfill that resolves it is written out,
  proved against a real MySQL schema, and safe to re-run.
- The endpoint reference lists every route the application serves, and the
  configuration section no longer claims the application boots without a
  database - it does not.

Two things to know before deploying, both covered in the sections named above:
requests that existed before Phase 5 need the backfill or they cannot be decided,
and the native `ENUM` columns mean a new status is a schema change.

Phase 5, for context, changed one thing about approving: a request is no longer
resolved by the first reviewer. It now moves through two sequential stages -
stage 1 requires `MANAGER`, stage 2 requires `IT_ADMIN` - and both are required.
A request stays `PENDING` between them, so `PENDING` now covers two points in the
workflow rather than one. The request's own lifecycle statuses are unchanged, and
all four terminal states are still terminal: a second transition of any kind is a
`409`.

Each stage's decision is a row in the new `access_request_stages` table rather
than a column on the request. The request keeps its four statuses and gains no
`stage1_*` or `stage2_*` columns, so a third stage would be new rows rather than
a schema change. A stage is created `PENDING` when the request is filed and is
decided at most once, which is a workflow record rather than an event log - the
history is still derived, and still needs no event table.

`AccessRequestResponse` gained a `stages` array alongside the existing fields;
every other endpoint response is unchanged apart from the point at which a
request becomes `APPROVED`.

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
| Frontend | Thymeleaf + a stylesheet (Phase 3) |

## Current Dependencies

Nothing is added before a module needs it. No versions are declared: the
`spring-boot-starter-parent` pins all of them.

| Dependency | Scope | Why |
|---|---|---|
| `spring-boot-starter-web` | compile | Embedded Tomcat + Spring MVC. Required to run the app at all. |
| `spring-boot-starter-data-jpa` | compile | Hibernate, the `EntityManager` and Spring Data repositories. Brings `spring-jdbc` + HikariCP. |
| `mysql-connector-j` | compile | The MySQL JDBC driver itself. |
| `spring-boot-starter-validation` | compile | Bean Validation on the request records. |
| `spring-boot-starter-security` | compile | Authentication and URL authorisation rules. |
| `spring-boot-starter-thymeleaf` | compile | The server-rendered pages. Brings thymeleaf + thymeleaf-spring6. |
| `spring-boot-starter-test` | test | JUnit 5, Mockito, Spring Test. |
| `spring-security-test` | test | `httpBasic(...)`, `csrf()` and `user(...)` support in the MockMvc tests. |

No templating is needed for the JSON API, and no JavaScript is used: the pages
are server-rendered and every form is a plain POST.

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
    │   │   ├── AccessFlowApplication.java
    │   │   ├── config/        SecurityConfig, WebMvc view configuration
    │   │   ├── controller/    AuthController, UserController, AccessRequestController
    │   │   ├── dto/           request/response records, PagedResponse, history entries
    │   │   ├── entity/        User, AccessRequest, AccessRequestStage
    │   │   ├── exception/     domain exceptions + GlobalExceptionHandler
    │   │   ├── repository/    Spring Data repositories
    │   │   ├── security/      UserDetailsService, principal
    │   │   ├── service/       business logic, transaction boundaries
    │   │   └── web/           Thymeleaf controllers, advice, view models
    │   └── resources
    │       ├── application.properties   committed, contains no secrets
    │       ├── static/css/              one stylesheet
    │       └── templates/               login, home, requests/*, error
    └── test
        └── java/com/accessflow
            ├── IntegrationTestSupport.java   shared harness, one actor per role
            ├── *ApiTest.java, *Test.java     API and browser tests
            └── web/                          tests scoped to com.accessflow.web
```

## Access Request Workflow

An employee files a request for access to a named application with a
justification. It starts as `PENDING` and is resolved exactly once, into
`APPROVED`, `REJECTED` or `WITHDRAWN`. A decision cannot be revised: a second
transition of any kind is a `409`.

Resolving it into `APPROVED` now takes two approvals rather than one:

```
PENDING --MANAGER approves--> PENDING --IT_ADMIN approves--> APPROVED
   |                              |
   |                              +--IT_ADMIN rejects--> REJECTED
   |
   +--MANAGER rejects--> REJECTED
   |
   +--applicant withdraws--> WITHDRAWN
```

The request stays `PENDING` between the two approvals, because that is still
its lifecycle state: it has not been decided yet. What tells a client which
approval is outstanding is the `stages` array on the response, not the status. A
rejection ends the request at whichever stage it lands, so stage 2 is never
reached after a stage-1 rejection.

The applicant is always taken from the authenticated caller. A request body
cannot name a different applicant.

| Rule | Behaviour |
|---|---|
| Who may file | Any authenticated user, for their own application |
| Who may review | `MANAGER` (stage 1), `IT_ADMIN` (stage 2), `SUPER_ADMIN` (either) |
| Out-of-turn decision | Refused with `403`; the role the stage asks for is enforced |
| Who may withdraw | The applicant only, never a reviewer, not even `SUPER_ADMIN` |
| Self-approval | Refused with `403`, even for `SUPER_ADMIN` |
| Rejection notes | Required |
| Approval notes | Optional |
| Withdrawal reason | Optional, 1000 characters |
| Reading a request | The applicant, or any reviewer |
| Listing all / filtering by status | Reviewers only |
| Paging the review queue | Reviewers only |

| Method | Path | Who |
|---|---|---|
| `POST` | `/api/access-requests` | any authenticated user |
| `GET` | `/api/access-requests/mine` | any authenticated user |
| `GET` | `/api/access-requests` | reviewer |
| `GET` | `/api/access-requests/status/{status}` | reviewer |
| `GET` | `/api/access-requests/page` | reviewer |
| `GET` | `/api/access-requests/{id}` | applicant or reviewer |
| `GET` | `/api/access-requests/{id}/history` | applicant or reviewer |
| `PATCH` | `/api/access-requests/{id}/approve` | reviewer, not the applicant |
| `PATCH` | `/api/access-requests/{id}/reject` | reviewer, not the applicant |
| `PATCH` | `/api/access-requests/{id}/withdraw` | the applicant |

Authentication is stateless HTTP Basic over BCrypt hashes. Responses never
contain a password or hash, and `AccessRequest.toString()` omits the
justification, the review notes and the withdrawal reason because free text can
quote secrets into logs and exception messages.

### Approval stages

Every request is created with two `access_request_stages` rows, both `PENDING`:
`stage_order` 1 requiring `MANAGER`, `stage_order` 2 requiring `IT_ADMIN`. They
exist from the moment the request is filed, not on first review, so a request
that is withdrawn or rejected before stage 1 still shows what it was waiting for.

`PATCH .../approve` and `PATCH .../reject` act on the *current* stage - the
first one still `PENDING` - rather than on a stage named in the request. A caller
whose role is not the one the current stage requires gets `403`, and so does a
reviewer acting out of turn after their stage has already passed. `SUPER_ADMIN`
may decide either stage, which keeps the one role that could always act in
Phases 2-4 able to act here.

A stage is decided at most once. The unique constraint on
`(request_id, stage_order)` is what guarantees it: without it a second stage 1
would make "the current stage" ambiguous, and one row's role check could pass
while another row stayed open for somebody else.

`reviewedBy`, `reviewedAt` and `reviewNotes` on the request record the reviewer
who *resolved* the request, not every reviewer who acted. They are written only
when the request is resolved, so a request approved by a `MANAGER` and still
waiting on `IT_ADMIN` has no `reviewedAt` and no `reviewedBy`. Each stage's own
decider, timestamp and notes are on the stage row.

There is no `SKIPPED` status. A request rejected at stage 1 never reaches stage 2,
so stage 2 is not "cancelled" - it has not been decided, and `PENDING` says
exactly that. What makes it unreachable is the request's own terminal status,
which is the thing the service checks first.

### Withdrawal

`PATCH /api/access-requests/{id}/withdraw` with an optional
`{"reason": "..."}` body. It records `withdrawnAt` and `withdrawalReason` and
leaves `reviewedBy`, `reviewedAt` and `reviewNotes` null: a withdrawn request
was not resolved by a reviewer, and writing a reviewer onto it would misattribute
an applicant's decision.

The body has no applicant, owner or id field. The only way to name the person
withdrawing is the authentication, so the action cannot be aimed at somebody
else's request by editing a payload - and a non-owner is refused with `403`
whatever their role, because a reviewer already has approve and reject, which is
the stronger action and one that can explain itself.

The three checks run in a fixed order: `404` if the request does not exist,
`403` if the caller does not own it, `409` if it is no longer `PENDING`. A
non-owner therefore cannot walk an id through a run of 403s and 409s to learn
whether it is still open.

An applicant may still withdraw after stage 1 has been approved, because the
request is still `PENDING` and therefore still open. The approval that already
happened is not erased by the withdrawal: it stays on the stage row and in the
history, which then reads `SUBMITTED`, `APPROVED`, `WITHDRAWN`.

### Request history

`GET /api/access-requests/{id}/history` returns the entries oldest first, each
with the actor, the timestamp and the free-text notes. A submission, then one
entry per decided stage, then the withdrawal if there was one:

| Outcome | Entries |
|---|---|
| Pending | `SUBMITTED` |
| Approved at stage 1, still pending | `SUBMITTED`, `APPROVED` |
| Approved at both stages | `SUBMITTED`, `APPROVED`, `APPROVED` |
| Rejected at stage 1 | `SUBMITTED`, `REJECTED` |
| Approved then rejected at stage 2 | `SUBMITTED`, `APPROVED`, `REJECTED` |
| Withdrawn before any decision | `SUBMITTED`, `WITHDRAWN` |
| Withdrawn after stage 1 | `SUBMITTED`, `APPROVED`, `WITHDRAWN` |

The action vocabulary is unchanged: `SUBMITTED`, `APPROVED`, `REJECTED`,
`WITHDRAWN`. A two-stage approval therefore reads as two `APPROVED` entries
with different actors, which is what the Phase 4 contract already described for
a resolved request - just with one more. The stages array on
`AccessRequestResponse` is where a client reads *which* stage each approval was,
if it needs to.

There is still no history table. The request row and its stage rows hold every
event, and each is written once and read back, so an event log would be a second
source of truth for the same facts with a second write path to keep consistent.
The derivation stops being possible the moment a terminal status gains an
outgoing transition.

The applicant and reviewers may read it; another employee gets `403`, the same
rule as the request itself, so history is not a way around that check.

### Paginated review queue

`GET /api/access-requests/page?status=&page=&size=` returns a `PagedResponse`:

```json
{
  "items": [], "page": 0, "size": 20,
  "totalElements": 0, "totalPages": 0, "first": true, "last": true
}
```

| Parameter | Default | Accepted |
|---|---|---|
| `status` | all statuses | any `Status`, including `WITHDRAWN` |
| `page` | `0` | zero-based, `>= 0` |
| `size` | `20` | `1` to `100` |

An out-of-range `page` or `size` is a `400` naming the parameter, not a clamped
value: silently turning a `size=1000` into `100` would answer a question nobody
asked and hide the truncation. A page past the end is an empty `200`, so a stale
bookmark degrades instead of erroring.

The order is fixed server-side at `createdAt DESC, id DESC` and is not
client-selectable; sending `sort` is a `400`. This is a paging requirement, not
tidiness: `createdAt` is not unique, and without the id tie-break a row whose
timestamp collides with another's can be returned on two pages or on none.

Spring's `Page` is never returned directly, because its JSON is a framework
implementation detail that has changed between versions. The envelope above is
this API's own.

### Endpoint reference

Every route the application actually serves, checked against the controller
mappings. "Reviewer" means `MANAGER`, `IT_ADMIN` or `SUPER_ADMIN`; "any
authenticated user" includes all four roles. Authentication is HTTP Basic, so
there is no token to obtain or refresh.

| Method | Path | Who | Request body | Success |
|---|---|---|---|---|
| `POST` | `/api/auth/login` | public | `email`, `password` | `200` |
| `POST` | `/api/users` | public | user fields, `role` ignored | `201` + `Location` |
| `POST` | `/api/users` | `SUPER_ADMIN` | user fields, `role` honoured | `201` + `Location` |
| `GET` | `/api/users` | reviewer | - | `200` list |
| `GET` | `/api/users/{id}` | any authenticated user | - | `200` |
| `GET` | `/api/users/employee/{employeeId}` | any authenticated user | - | `200` |
| `GET` | `/api/users/email/{email}` | any authenticated user | - | `200` |
| `POST` | `/api/access-requests` | any authenticated user | `application`, `justification` | `201` + `Location` |
| `GET` | `/api/access-requests` | reviewer | - | `200` list |
| `GET` | `/api/access-requests/mine` | any authenticated user | - | `200` list, own requests only |
| `GET` | `/api/access-requests/status/{status}` | reviewer | - | `200` list |
| `GET` | `/api/access-requests/page` | reviewer | `status`, `page`, `size` query | `200` `PagedResponse` |
| `GET` | `/api/access-requests/{id}` | applicant or reviewer | - | `200` |
| `GET` | `/api/access-requests/{id}/history` | applicant or reviewer | - | `200` list |
| `PATCH` | `/api/access-requests/{id}/approve` | stage reviewer, not the applicant | `notes` optional | `200` |
| `PATCH` | `/api/access-requests/{id}/reject` | stage reviewer, not the applicant | `notes` required | `200` |
| `PATCH` | `/api/access-requests/{id}/withdraw` | the applicant only | `reason` optional | `200` |
| anything else | under `/api` | - | - | `403`, by the chain |

Three things in that table are worth reading twice:

- `POST /api/users` is public *and* role-bearing, which is contradictory on its
  face. The rule is that the `role` in the body is honoured only for a
  `SUPER_ADMIN` caller; for anyone else the request is created as `EMPLOYEE`
  whatever was asked for.
- `approve` and `reject` take an optional and a mandatory `notes` respectively.
  Approval notes are a record; a rejection without a reason is not something the
  UI offers and the API refuses.
- The last row is not a placeholder. The API chain ends in `denyAll()`, so a
  route that is not in this table is refused with `403` rather than falling
  through to the servlet - a URL that does not exist cannot be reached by
  accident.

No route accepts a caller-supplied applicant, reviewer or status. The acting
user is read from the authentication on every request, and the transitions take
only an id and a note.

### Error format

Every failure returns the same JSON body:

| Status | Raised for |
|---|---|
| `400` | validation failure, malformed body, unsupported `status` value, out-of-range `page`/`size`, client-chosen `sort` |
| `401` | missing or bad credentials |
| `403` | wrong role, self-approval, or reading or withdrawing somebody else's request |
| `404` | unknown user or request |
| `409` | duplicate user, or a second transition of an already-resolved request |

Two of these are worth naming. A constraint on a `@Valid` request body arrives
as `MethodArgumentNotValidException`; a constraint on a handler parameter -
`?page=-1`, `?size=1000`, `?sort=createdAt` - arrives as
`HandlerMethodValidationException`, and it is mapped to the same body. Left
unmapped it would be answered by Spring with a shape and a status the API has
never used before, and the paginated endpoint's errors would differ from the
rest of the API's.

## Browser UI

The same workflow, driven from pages. Sign in at `/login` with the email address
and the password; the session cookie is what keeps you signed in. A fresh local
database has no account to sign in with, so use the seeded development
administrator: see **LOCAL DEVELOPMENT LOGIN** below.

| Method | Path | Who |
|---|---|---|
| `GET` | `/login` | anyone |
| `GET` | `/` | any authenticated user |
| `GET` | `/requests` | any authenticated user (own requests) |
| `GET`, `POST` | `/requests/new` | any authenticated user |
| `GET` | `/requests/{id}` | applicant or reviewer |
| `GET` | `/requests/review` | reviewer |
| `POST` | `/requests/{id}/approve` | reviewer, not the applicant |
| `POST` | `/requests/{id}/reject` | reviewer, not the applicant |
| `POST` | `/requests/{id}/withdraw` | the applicant |
| `POST` | `/logout` | any authenticated user |

`{id}` is a number: the mapping is `/requests/{id:\d+}`, so `/requests/review`
is matched by its own rule and never swallowed as an id. A non-numeric id such
as `/requests/abc` is not routed to the detail page at all - it passes the
chain, finds no handler, and is answered with the `404` error page. A path the
chain refuses outright never reaches that point and stays a `403`. `/logout` is
Spring Security's form logout rather than a controller method; it is a `POST`
because the browser chain keeps CSRF on.

Every rule in the workflow table above applies unchanged: the pages call
`AccessRequestService`, the same object the API calls, and none of the
authorisation lives in a controller.

| Concern | How the pages hold to it |
|---|---|
| Who is acting | The authenticated principal only. A form has no applicant, reviewer or status field, and a submitted one is ignored. |
| Refusals | The chain refuses first and the service refuses again, so a page cannot widen either. |
| CSRF | On for the browser chain. Every form is a POST carrying the token Thymeleaf writes for `th:action`. |
| Passwords | Never reach a page: no view model field holds one, and the tests assert no page contains the password or a hash. |
| Free text | Rendered with `th:text`, never `th:utext`, so a stored `<script>` is shown, not run. |

### What the pages add in Phase 4

| Page | Change |
|---|---|
| `requests/detail.html` | withdrawal form for the applicant of a pending request, a withdrawn section, and the history list |
| `requests/mine.html` | a withdrawn row reads "Withdrawn by you" instead of "Waiting for a reviewer" |
| `requests/review.html` | one page at a time with Previous/Next, and `WITHDRAWN` in the filter |

The forms are gated on model flags (`canWithdraw`, `canReview`) that the
controller derives, and those flags are presentation only: the POST handlers
re-check every rule through `AccessRequestService`, so hiding a button is never
what enforces a rule.

### What the pages add in Phase 5

| Page | Change |
|---|---|
| `requests/detail.html` | an approval-stage timeline, the outstanding stage named, and review forms drawn only for the role that stage asks for |
| `requests/review.html` | a "Next stage" column, so a reviewer can see a request already waiting on somebody else before acting on it |

`canReview` is now narrower than it was. In Phase 4 it meant "the viewer is not
the applicant and the request is `PENDING`"; in Phase 5 it also means "the viewer
holds the role the current stage requires". The controller asks
`AccessRequestService.isStageReviewer` rather than repeating the rule, so the
page and the service cannot disagree about who may act.

`mine.html` had a genuine bug, and the withdrawal state is what exposed it. The
"Decision" column fell back to "Waiting for a reviewer" whenever `reviewedAt`
was null, which is true for a withdrawn request - a row that nobody will ever
review, described as one still waiting. The fix keys on the withdrawal state
rather than on the absence of a decision.

`review.html` does not carry the page number in the filter form: applying a new
status always starts at page 0, because carrying the current page over lands the
reviewer past the end of a shorter result set and shows them an empty table. The
Previous/Next links do carry both the filter and the page size, so paging cannot
silently drop either.

### Two security chains

The API and the pages authenticate differently, so `SecurityConfig` declares
two filter chains and the first one whose `securityMatcher` accepts the request
wins.

| | API chain (order 1) | Browser chain (order 2) |
|---|---|---|
| Matches | `/api/**` | everything else |
| Authentication | stateless HTTP Basic | session cookie, form login |
| CSRF | off - no cookie is attached automatically | on |
| Unlisted path | denied, JSON | denied, HTML error page |
| Refusal format | `ErrorResponse` JSON | the shared `error` page |

A browser session is not an API credential: the API chain never reads the
cookie, so a page cannot be turned into a way around Basic auth.

### Error page

One template, `templates/error.html`, reached three ways: a failure inside a
page (`WebViewAdvice`), a URL that matches no controller
(`WebErrorPageResolver`), and a refusal decided in the filter chain
(`SecurityConfig`). All three fill the same model, so the status, the reason
and the path look the same wherever the user lands. The unexpected-exception
case always shows a fixed sentence and logs the detail instead, because an
exception message can quote internal state and this HTML goes to a browser.

## Database schema

### Development

`spring.jpa.hibernate.ddl-auto=update` creates and alters the schema on
startup, which is what the development grants in `db/setup.sql` section 4 exist
for. No migration tool is in use and none is needed at this size: the schema is
small, it is owned by one application, and there is no second environment that
has to be kept in step with the code. The cost of that choice is that the schema
is whatever the last run made it, which is why the DDL below is written out
explicitly.

`db/setup.sql` deliberately creates no tables.

### Production

`ddl-auto=update` is not appropriate in production. It can issue DDL as the
application user, it does not drop what it does not recognise, and it cannot
express a backfill that a column change requires. The application user should
hold only the DML grants from section 3, and the DDL below should be applied by
a DBA or by whatever migration tooling is adopted first.

The application overrides both development-only settings through the
environment, so the committed file does not have to be edited to deploy it - see
the configuration section.

### The current schema

This is the schema read back from a live database after the application has run
`ddl-auto=update` on an empty schema. `db/setup.sql` creates the database and the
user; the tables arrive on first run.

Column order below is grouped for reading. The physical order is whatever
Hibernate appended, and it carries no meaning.

```sql
CREATE TABLE users (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    employee_id VARCHAR(50)  NOT NULL,
    email       VARCHAR(255) NOT NULL,
    password    VARCHAR(255) NOT NULL,
    first_name  VARCHAR(100) NOT NULL,
    last_name   VARCHAR(100) NOT NULL,
    role        ENUM('EMPLOYEE','IT_ADMIN','MANAGER','SUPER_ADMIN') NOT NULL,
    department  VARCHAR(100) NULL,
    active      BIT(1)       NOT NULL,
    created_at  DATETIME(6)  NOT NULL,
    updated_at  DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_users_email       UNIQUE (email),
    CONSTRAINT uk_users_employee_id UNIQUE (employee_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE access_requests (
    id                BIGINT        NOT NULL AUTO_INCREMENT,
    applicant_id      BIGINT        NOT NULL,
    application       VARCHAR(100)  NOT NULL,
    justification     VARCHAR(1000) NOT NULL,
    status            ENUM('APPROVED','PENDING','REJECTED','WITHDRAWN') NOT NULL,
    reviewed_by_id    BIGINT        NULL,
    reviewed_at       DATETIME(6)   NULL,
    review_notes      VARCHAR(1000) NULL,
    withdrawn_at      DATETIME(6)   NULL,
    withdrawal_reason VARCHAR(1000) NULL,
    created_at        DATETIME(6)   NOT NULL,
    updated_at        DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_access_requests_status_created    (status, created_at, id),
    INDEX idx_access_requests_created           (created_at, id),
    INDEX idx_access_requests_applicant_created (applicant_id, created_at, id),
    CONSTRAINT fk_applicant FOREIGN KEY (applicant_id)   REFERENCES users (id),
    CONSTRAINT fk_reviewer  FOREIGN KEY (reviewed_by_id) REFERENCES users (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE access_request_stages (
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    request_id    BIGINT      NOT NULL,
    stage_order   INT         NOT NULL,
    required_role ENUM('EMPLOYEE','IT_ADMIN','MANAGER','SUPER_ADMIN') NOT NULL,
    status        ENUM('APPROVED','PENDING','REJECTED') NOT NULL,
    decided_by_id BIGINT      NULL,
    decided_at    DATETIME(6) NULL,
    notes         VARCHAR(1000) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_access_request_stages_request_order UNIQUE (request_id, stage_order),
    CONSTRAINT fk_stage_request FOREIGN KEY (request_id)    REFERENCES access_requests (id),
    CONSTRAINT fk_stage_decider FOREIGN KEY (decided_by_id) REFERENCES users (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
```

**Index and unique-constraint names above are exact.** They are declared by name
in the entity annotations, so they are in the database and in any future
migration tool that reads this file.

**Foreign-key names are not, and cannot be.** Hibernate generates them as
`FK` plus a hash - the live table has `FK9jstx3xpblhwgonm4xqqrxt5j`,
`FKiggwsyx01smmy31gjrhvbpkte`, `FKh7awrt1jxvt6sw35326rxnjcn` and
`FKoetnlwkvnq68op3ckdu7f0nr9`. Those values depend on the Hibernate version and
are not stable, and no name, column or behaviour depends on them. The readable
names above are a convention for hand-written DDL: a DBA applying this is free to
use any names, because MySQL resolves the reference by column, not by constraint
name.

| | Where it comes from |
|---|---|
| `users` | Phase 1B |
| `access_requests` | Phase 2, with the withdrawal columns from Phase 4 |
| `access_request_stages` | Phase 5 |
| `created_at` / `updated_at` | Written by Hibernate (`@CreationTimestamp` / `@UpdateTimestamp`), not by the application |
| `active` | `BIT(1)`; an inactive account keeps its row and its hash but cannot authenticate |

**Enumerated columns are MySQL `ENUM`, not `VARCHAR`.** The entities declare
`@Enumerated(EnumType.STRING)`, and Hibernate 6 maps that to a native `ENUM`
column on MySQL. Four consequences worth knowing before a release:

- The Java enum is the single definition of the *values*. A value is stored as
  its name, and the column will not accept a name the Java enum does not declare.
- **Adding a value to a Java enum is a schema change, not just a code change.**
  `ddl-auto=update` does not alter an existing `ENUM` column, so a new status
  would fail on write with a data-truncation error. Any such change needs
  `ALTER TABLE ... MODIFY` in the production DDL above, applied by a DBA.
- **`ORDER BY` on an `ENUM` column sorts by the stored position, not
  alphabetically.** No query in this project orders by an enum, so nothing here
  depends on it today.
- **The stored position is not always the order the Java enum declares.** The
  two `status` columns are stored as `APPROVED` first, while `Status` declares
  `PENDING` first - an artefact of the columns having been extended in place
  rather than created once.
- **An omitted `NOT NULL ENUM` column takes the *first stored value*, silently.**
  MySQL applies this even under `STRICT_TRANS_TABLES` and reports no error, so a
  hand-written `INSERT` that leaves `status` out writes `APPROVED` here rather
  than `PENDING`. Hibernate cannot hit it, because the entity always sets the
  field - but any script, migration or data fix written against these tables can,
  and the failure is invisible. **Name every enum column explicitly in SQL.**
- Nothing in the application sorts by an enum column, so the position is not
  observable today. It matters to whoever writes the next `ALTER TABLE`:
  **restate the full value list in the order shown above**, or the ordinals of
  every existing value silently change underneath the rows that already use them.

Each index is an equality prefix plus the sort the queries use, in that order -
`(status, ...)` serves the filtered queue, `(created_at, id)` the unfiltered
one, `(applicant_id, ...)` an employee's own list. The `id` column is the
tie-break that makes the order unique; see the pagination section for why a
paged query needs that.

The unique constraint on `access_request_stages` is
`(request_id, stage_order)`. Its leftmost column is `request_id`, so it doubles
as the index the stage reads use: they all ask
`WHERE request_id = ? ORDER BY stage_order`, and a separate index on
`request_id` alone would be redundant. It is also what makes a duplicate stage
position impossible, which is what keeps "the current stage" unambiguous.

Phase 4's changes to `access_requests` are the two nullable withdrawal columns
and the three indexes above. `status` was not retyped: `WITHDRAWN` is a value
added to the same enum, not a wider column.

### Requests filed before the stages existed

`access_request_stages` was created in Phase 5, so a request filed before it has
no stage rows. `ddl-auto=update` creates the table; it does not invent the rows
a request never had, and nothing in the application backfills them at runtime.
This is deliberate - a backfill would have to name a decider, and inventing a
historical decision is worse than leaving the gap visible.

What such a request actually does, verified by
`LegacyRequestWithoutStagesTest`:

| | Behaviour |
|---|---|
| Read, list, filter, page | Normal. `stages` is an empty array. |
| `nextStage()` | `null`, so no page presents it as awaiting a stage. |
| Approve or reject | **Refused with `409`.** There is no outstanding stage to decide, so the request cannot be resolved at all. |
| Withdraw | Still works. It does not go through a stage, and it is the only transition left. |
| History | `SUBMITTED` only. No stage decision is invented. |
| A request already resolved before Phase 5 | Reads normally. Its `reviewedBy`/`reviewedAt` are on the request row, and the response does not depend on the stages. |

So an un-backfilled `PENDING` request is stuck: visible, withdrawable, and not
decidable. **Review every such row before the application is deployed against a
table that has any.**

Do it in this order: create `access_request_stages` (the DDL above), then run the
backfill, then deploy. A request that has no stage rows at all is what the script
is for.

```sql
START TRANSACTION;

-- Stage 1 for every open request that does not already have one.
INSERT INTO access_request_stages (request_id, stage_order, required_role, status)
SELECT r.id, 1, 'MANAGER', 'PENDING'
FROM access_requests r
WHERE r.status = 'PENDING'
  AND NOT EXISTS (
      SELECT 1 FROM access_request_stages s
      WHERE s.request_id = r.id AND s.stage_order = 1
  );

-- Stage 2 for every open request that does not already have one.
INSERT INTO access_request_stages (request_id, stage_order, required_role, status)
SELECT r.id, 2, 'IT_ADMIN', 'PENDING'
FROM access_requests r
WHERE r.status = 'PENDING'
  AND NOT EXISTS (
      SELECT 1 FROM access_request_stages s
      WHERE s.request_id = r.id AND s.stage_order = 2
  );

COMMIT;
```

**`status` is named in the insert on purpose, and it must stay there.** It is not
a redundant column. `access_request_stages.status` is `NOT NULL` with no
`DEFAULT`, and MySQL fills an omitted `NOT NULL ENUM` with the **first value in
the stored list** - which is `'APPROVED'`, not `'PENDING'` - and it does this
even on a server running `STRICT_TRANS_TABLES`, with no error. An insert that
omits the column therefore succeeds and writes a stage claiming somebody already
approved it: a fabricated approval, invented in bulk, which is the single
outcome this script exists to avoid. Hibernate never hits this because the entity
always sets the field, so it is a hazard specific to hand-written SQL against
this table.

Five properties, each one a deliberate choice:

- **It is re-runnable.** Each insert carries a `NOT EXISTS` guard on
  `(request_id, stage_order)`, so a request that already has that stage is
  skipped rather than inserted a second time. The unique constraint
  `uk_access_request_stages_request_order` is the backstop: a genuine duplicate
  cannot be committed even if the guard is wrong. The `NOT EXISTS` matters for the
  case the constraint alone handles badly - a request that has stage 1 but not
  stage 2, which a bare `INSERT ... SELECT` would abort the whole run on. The
  guard fills the gap and leaves the partial row alone rather than "fixing" it.
- **It never touches a resolved request.** It filters on `status = 'PENDING'`, so
  no `APPROVED`, `REJECTED` or `WITHDRAWN` row is read or written. Those keep
  their decision on the request row, which is what the read path already handles.
- **It invents no reviewer.** Both rows are written `PENDING` with no
  `decided_by_id` and no `decided_at`, so no historical approval is fabricated. A
  `PENDING` request that was already being worked on therefore restarts at stage 1
  for whoever acts on it next. Whether that is right is a question about the data,
  not the schema, so it is a decision for whoever owns it.
- **It only inserts.** No `UPDATE`, no `DELETE`, no `DROP`, no `TRUNCATE`. Taken
  after a backup, and it cannot lose a row.
- **It is executed by the test suite.** `BackfillScriptTest` runs these exact
  statements against a real MySQL schema - twice in a row, on a partially staged
  request, and alongside already-resolved ones - and checks the README still
  contains them.

Check the shape of the data before, and the result after:

```sql
-- Before: what stage rows already exist?
SELECT request_id, stage_order, status FROM access_request_stages
ORDER BY request_id, stage_order;

-- After: must return zero rows. Any row here is a request the script could not
-- leave with both stages - investigate it rather than backfilling it by hand.
SELECT r.id AS request_id, COUNT(s.id) AS stage_count
FROM access_requests r
LEFT JOIN access_request_stages s ON s.request_id = r.id
WHERE r.status = 'PENDING'
GROUP BY r.id
HAVING stage_count <> 2;

-- After: must also return zero rows. This is the check for the trap above, and it
-- is the one worth running even when the count is right.
SELECT request_id, stage_order FROM access_request_stages
WHERE status <> 'PENDING' OR decided_by_id IS NOT NULL;
```

If the second query returns anything, stop. A `PENDING` request with zero or one
stage is either outside what the script does safely, or a request whose partial
state needs a decision from someone who owns the data. If the third returns
anything, the script was run in a version that omitted `status` - the stages
claim to be approved. Delete those rows and re-run the script above; the
requests themselves are untouched, because the script only ever inserts.

## Configuration

`src/main/resources/application.properties` is committed and contains no secret.
Every value that differs between a laptop and a deployed system comes from the
environment, so the same file is used everywhere and is never edited to deploy.

| Property | Default | Production |
|---|---|---|
| `spring.datasource.url` | `localhost:3306/accessflow`, built from four variables | set the variables |
| `spring.datasource.username` | `accessflow` | set the variable |
| `spring.datasource.password` | empty | **required** |
| `server.port` | `8080` | usually fine |
| `spring.jpa.hibernate.ddl-auto` | `update` | `validate` |
| `spring.jpa.show-sql` | `true` | `false` |
| `spring.jpa.open-in-view` | `false` | `false` |

Two of these are development-only and are switched off by setting the matching
variable, not by editing the file:

| Variable | Effect | Production |
|---|---|---|
| `ACCESSFLOW_DDL_AUTO` | value of `spring.jpa.hibernate.ddl-auto` | `validate` |
| `ACCESSFLOW_SHOW_SQL` | value of `spring.jpa.show-sql` | `false` |

`ddl-auto=validate` is the right production setting rather than `none`: `none`
lets a missing table surface as "Table 'accessflow.users' doesn't exist" on the
first query, while `validate` refuses to start if the schema does not match the
entities. Either way the production schema is applied by a DBA, not by the
application.

`show-sql` logs every statement Hibernate emits, pretty-printed by
`hibernate.format_sql`. It logs the **statements, not the bound values** - an
insert appears with `?` placeholders - so what reaches a log aggregator is the
shape of every query and the name of every column, not the request data itself.
Bound values do appear if the `org.hibernate.SQL` logger is turned up to `TRACE`,
which is a second reason to leave both alone in production. It is invaluable
while learning, which is why it defaults to on for development and has to be
switched off with `ACCESSFLOW_SHOW_SQL=false`.

`logging.level.org.hibernate.tool.schema=ERROR` is deliberate. Hibernate logs
schema-creation failures at `WARN`, which is easy to miss - the application then
starts with no tables and the problem only appears later as a missing-table
error. Raising it to `ERROR` makes a failed `ddl-auto` visible immediately.

The empty default on the password is deliberate, and it is worth being precise
about what it does, because it is not what it looks like. **The application does
not boot without a database.** Hibernate has to read JDBC metadata to choose a
dialect, so with no password it fails during startup with
`Unable to determine Dialect without JDBC metadata` - it never gets as far as
serving a request. The empty default is what lets the **test build** run with no
database: the database-backed test classes are gated on
`ACCESSFLOW_DB_PASSWORD` and report as *skipped* instead of failing, so a fresh
clone builds green on a machine with no MySQL. See the credentials section.

Static resources and Thymeleaf are Spring Boot's defaults: `classpath:/static/`
for `/css/**` and the classpath `/templates/` location, with no template caching
turned off and no prefix configured.

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

Two more variables change how the application is allowed to touch the schema,
and are documented in the configuration section above:
`ACCESSFLOW_DDL_AUTO` and `ACCESSFLOW_SHOW_SQL`.

### One-time setup (Windows)

1. Create the database and application user by running `db/setup.sql`
   in MySQL Workbench as `root` (replace the placeholder password first).
2. Set the password in your shell, this session only:

   ```powershell
   $env:ACCESSFLOW_DB_PASSWORD = "your-password"
   ```

3. Run the tests:

   ```powershell
   mvn clean test
   ```

   Without the variable every database test is **skipped** and the build still
   passes, so a fresh clone builds cleanly on a machine with no database.

   The gate lives on each concrete test class rather than on the shared
   `IntegrationTestSupport`, because `@EnabledIfEnvironmentVariable` is not
   inherited. A new test class must therefore repeat the annotation:

   ```java
   @EnabledIfEnvironmentVariable(named = "ACCESSFLOW_DB_PASSWORD", matches = ".+")
   ```

   Tests are never disabled to force a green build; without the credential they
   report as *skipped*, which is the honest result.

### Making it permanent

For real development you want the variable available every time:

- **System-wide:** `SystemPropertiesAdvanced` -> `Environment Variables` -> add
  `ACCESSFLOW_DB_PASSWORD`. Requires a new terminal, and it is readable by
  every user on the machine.
- **Per-project (preferred for a laptop):** Eclipse -> `Run Configurations` ->
  select the run config -> `Environment` tab -> add the variable. It is stored
  in the workspace, not in the repository.
- **Per-shell:** the `$env:` form above, in the PowerShell profile.

### A missing credential

Worth stating plainly, because the empty default in `${ACCESSFLOW_DB_PASSWORD:}`
looks like it makes the credential optional. It does not:

- **The test build runs without one.** The database-backed classes are gated on
  the variable and report as *skipped*, so `mvn clean test` is green on a machine
  with no MySQL.
- **The application does not run without one.** It fails during startup, with
  Hibernate's `Unable to determine Dialect without JDBC metadata`. The failure is
  immediate rather than deferred to the first query, which is the safer outcome:
  a misconfigured deployment never serves a request.

There is deliberately no "make it strict" switch to set. Changing the property to
`${ACCESSFLOW_DB_PASSWORD}` - no default - would turn a clear skip into a failure
of *every* test class, including the ones that need no database, which is a worse
trade for a repository that is cloned without a database. If a deployment ever
needs a friendlier message than Hibernate's, that is a startup check in code
rather than a properties change.

## LOCAL DEVELOPMENT LOGIN

**Local development only. Not a production credential.**

| | |
|---|---|
| **Email** | `admin@accessflow.local` |
| **Password** | `AccessFlow@123` |
| **Role** | `SUPER_ADMIN` |

> **Change or remove this account before any production deployment.**
> The password is published in this file on purpose, so it is safe only for a
> laptop. It is not a secret, it is not a production credential, and it must
> never be treated as one.

### Why it exists

A fresh database has no way to reach `SUPER_ADMIN`. Registration is public, but
`UserController` forces every self-registered account to `EMPLOYEE`, and the only
other way to set the role is to already be a `SUPER_ADMIN`. So on a clean local
database the application starts, `/login` is served, and **every** credential is
rejected - there is no account to sign in with and no way to create one. This
account is that missing first account, and it is the only way to reach
`SUPER_ADMIN`. The fifty ordinary employees described in *Demo Accounts* below are
seeded alongside it, and none of them is a reviewer.

### How it is created

`DevAdminSeedRunner` runs on startup **only under the `local` profile**. The
`DevEmployeeSeedRunner` that seeds the fifty demo employees is gated the same way:

```bash
# local development, with the development administrator seeded
mvn spring-boot:run -Dspring-boot.run.profiles=local

# or, from a shell
$env:SPRING_PROFILES_ACTIVE = "local"
mvn spring-boot:run

# or, from the jar
$env:SPRING_PROFILES_ACTIVE = "local"
java -jar target/accessflow-0.0.1-SNAPSHOT.jar
```

Start the application without the profile and nothing is seeded, because the
runner bean does not exist at all - a deployment would have to be started with
`--spring.profiles.active=local` to create the account, which is a deliberate,
visible act. No other profile enables it, and no profile file changes anything
else, so `local` affects only this runner.

The account is an ordinary user row with `employee_id` `DEV-SUPERADMIN`, created
through `UserService.createUser`, so:

- **The password is stored as a BCrypt hash**, never as plaintext. `createUser`
  hashes with the application's `BCryptPasswordEncoder` before saving, which is
  the only reason the known password above can be used to sign in at all. The
  plaintext exists in exactly two places: the constant in `DevAdminSeeder` and
  this README.
- **Nothing bypasses authentication.** The account is reached through the
  ordinary `CustomUserDetailsService`, so a wrong password is still rejected, a
  deactivated account is still refused, and the self-approval rule still applies
  to it like to any other `SUPER_ADMIN`.
- **It is not available through public registration.** `POST /api/users` cannot
  reuse its email or employee id, and cannot mint a `SUPER_ADMIN` of its own.

### Running it more than once is safe

Every startup re-runs the seed, so it is written to be idempotent:

| State of the database | Result |
|---|---|
| Email absent | The account is created, and the log says so as a warning. |
| Email already present | Nothing is written. The account keeps its password, role and every other field. |
| `DEV-SUPERADMIN` held by a different account | Nothing is written and the conflict is logged. The other account is not modified. |

The check and the insert share one transaction, and the existing unique
constraints on `email` and `employee_id` remain the real backstop - the same
protection every other user creation has.

Nothing about the seed is written to `application.properties`,
`db/setup.sql`, a SQL `INSERT`, or a log. The password never reaches a log line
or a test report.

## Demo Accounts

**Development / college-demonstration credentials only. Not production
credentials.** Change or remove every account in this section before any real
deployment.

AccessFlow Technologies Pvt. Ltd. is demonstrated with a room full of people, so
the application seeds a full roster alongside the administrator. Start it with the
`local` profile and both sets appear:

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

Without the `local` profile neither is created, because neither runner bean is
registered - see *LOCAL DEVELOPMENT LOGIN* above for why that profile is the only
gate, and for the `SUPER_ADMIN` account.

### Employees

| | |
|---|---|
| **How many** | 50 |
| **Employee ids** | `EMP-1001` to `EMP-1050` |
| **Email** | `<firstname>.<lastname>@accessflow.local`, lower case |
| **Password** | `Employee@123` - the same for all fifty |
| **Role** | `EMPLOYEE` |
| **Active** | yes |
| **Department** | Information Technology |

Sign in at <http://localhost:8080/login> with the address in lower case:

| Employee id | Email | Password |
|---|---|---|
| `EMP-1001` | `rahul.sharma@accessflow.local` | `Employee@123` |
| `EMP-1002` | `amit.patil@accessflow.local` | `Employee@123` |
| `EMP-1003` | `neha.deshmukh@accessflow.local` | `Employee@123` |
| `EMP-1004` | `priya.kulkarni@accessflow.local` | `Employee@123` |
| `EMP-1005` | `rohan.joshi@accessflow.local` | `Employee@123` |
| `EMP-1006` | `sneha.pawar@accessflow.local` | `Employee@123` |
| ... | ... | ... |
| `EMP-1050` | `ashwin.patel@accessflow.local` | `Employee@123` |

The address is derived from the name, so it is always the lower-cased first and
last name joined by a dot. A first name that is already taken gets a different
surname, never a suffix: there are no `rahul.sharma2@...` addresses.

The names are realistic and drawn from different regions of India - Maharashtra,
Kerala, Tamil Nadu, Karnataka, Andhra Pradesh, Telangana, Bengal, the north and
Bhopal - so a class reads a list of colleagues rather than a list of placeholders.

### What the employees are and are not

They are deliberately **not** reviewers. That is the point of seeding them as
`EMPLOYEE`: the demonstration shows the permission model working, so an employee
can file and track a request, is refused the review queue, and still cannot
approve anything. Approving needs a `MANAGER` and then an `IT_ADMIN`, so a
two-stage demo has to use accounts that really hold those roles - either real ones
you create, or the administrator, which is a `SUPER_ADMIN` and may decide either
stage.

If a demo needs a named `MANAGER` or `IT_ADMIN` to sign in as, create one with
`POST /api/users` while authenticated as the administrator and a `role` in the
body; an unauthenticated registration is always forced to `EMPLOYEE`.

### The password is hashed, not stored

`Employee@123` appears in exactly two places in this repository: the constant in
`DevEmployeeSeeder` and this section. The database holds a BCrypt hash, produced
by the application's own `BCryptPasswordEncoder` through `UserService.createUser` -
the same path every other account takes. Each of the fifty has its own hash,
because BCrypt salts every hash, so the table does not contain fifty copies of one
value. No BCrypt hash is written in this README, and the seed writes nothing to
`db/setup.sql`, to `application.properties`, to a SQL `INSERT` or to a log.

### Running it more than once is safe

Every startup re-runs both seeds, and both are idempotent:

| State of the database | Result |
|---|---|
| Employee absent | The account is created. |
| Email already present | Nothing is written. The account keeps its password, role, name and every other field. |
| Employee id held by a different account | Nothing is written and the conflict is logged. That account is not modified. |

So a password changed on a demo account stays changed, and an employee id reused
for a real person is never overwritten to make the demo look tidy.

## Build & Run

```bash
# compile and run tests
mvn clean test

# start the application (http://localhost:8080)
mvn spring-boot:run

# start it with the LOCAL DEVELOPMENT LOGIN account seeded
mvn spring-boot:run -Dspring-boot.run.profiles=local

# build an executable jar
mvn clean package
java -jar target/accessflow-0.0.1-SNAPSHOT.jar
```

## Main Class

`com.accessflow.AccessFlowApplication`
