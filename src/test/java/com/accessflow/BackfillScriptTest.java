package com.accessflow;

import java.util.List;

import com.accessflow.dto.AccessRequestCreateRequest;
import com.accessflow.entity.AccessRequest;
import com.accessflow.entity.User;
import com.accessflow.repository.AccessRequestRepository;
import com.accessflow.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 6 - the backfill script documented in the README, executed.
 *
 * {@link LegacyRequestWithoutStagesTest} pins the behaviour of a request with no
 * stage rows. This pins the way out of it. The script is the only thing standing
 * between an existing deployment and a table of stuck requests, and until now it
 * has been prose: a deployer would have had to take the README's word that the SQL
 * runs, that it can be run twice, and that it leaves a resolved request alone.
 *
 * The statements here are the ones in the README, character for character, and the
 * last test checks that the file still contains them - so a change to one without
 * the other fails the build rather than passing a check against stale SQL.
 *
 * Everything runs inside the test transaction, which rolls back, so the script is
 * exercised against the real schema and leaves nothing behind.
 */
@DisplayName("Phase 6 - the documented stage backfill")
@EnabledIfEnvironmentVariable(named = "ACCESSFLOW_DB_PASSWORD", matches = ".+")
class BackfillScriptTest extends IntegrationTestSupport {

    /**
     * Stage 1 of the documented backfill.
     *
     * <h2>Why {@code status} is named here and not left to a default</h2>
     *
     * The column is {@code NOT NULL} with no {@code DEFAULT}, so it looks like it
     * has to be supplied. Supplying it is what this script was originally written
     * not to do, and the result was not an error - it was a silent one.
     *
     * MySQL treats the first value of a {@code NOT NULL} {@code ENUM} as its
     * implicit default, and applies it even under {@code STRICT_TRANS_TABLES},
     * which this server runs. The first stored value of
     * {@code access_request_stages.status} is {@code 'APPROVED'}, not
     * {@code 'PENDING'}, so an {@code INSERT} that omits the column wrote a stage
     * that claims a manager already approved it - inventing a historical
     * decision, which is the one thing the backfill exists not to do. The tests
     * below fail on exactly that: stage 1 comes back {@code APPROVED}.
     *
     * <h2>Why the NOT EXISTS guard is here</h2>
     *
     * Without it, a request that already has a stage 1 - because the backfill ran
     * before, or because somebody fixed one request by hand - makes this statement
     * abort the entire run on the unique constraint, taking the stage 2 rows that
     * had not been inserted yet with it.
     */
    private static final String BACKFILL_STAGE_ONE = """
            INSERT INTO access_request_stages (request_id, stage_order, required_role, status)
            SELECT r.id, 1, 'MANAGER', 'PENDING'
            FROM access_requests r
            WHERE r.status = 'PENDING'
              AND NOT EXISTS (
                  SELECT 1 FROM access_request_stages s
                  WHERE s.request_id = r.id AND s.stage_order = 1
              )""";

    private static final String BACKFILL_STAGE_TWO = """
            INSERT INTO access_request_stages (request_id, stage_order, required_role, status)
            SELECT r.id, 2, 'IT_ADMIN', 'PENDING'
            FROM access_requests r
            WHERE r.status = 'PENDING'
              AND NOT EXISTS (
                  SELECT 1 FROM access_request_stages s
                  WHERE s.request_id = r.id AND s.stage_order = 2
              )""";

    @Autowired
    private AccessRequestRepository accessRequestRepository;

    @Autowired
    private UserRepository userRepository;

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    @DisplayName("The backfill gives a stage-less pending request both stages and no reviewer")
    void backfillSeedsBothStages() {
        Long id = fileStageLessRequest("AWS");

        runBackfill();

        List<Object[]> stages = stagesOf(id);

        assertThat(stages).hasSize(2);

        assertThat(stageOrder(stages.get(0))).isEqualTo(1);
        assertThat(requiredRole(stages.get(0))).isEqualTo("MANAGER");
        assertThat(stageStatus(stages.get(0))).isEqualTo("PENDING");
        assertThat(stageOrder(stages.get(1))).isEqualTo(2);
        assertThat(requiredRole(stages.get(1))).isEqualTo("IT_ADMIN");
        assertThat(stageStatus(stages.get(1))).isEqualTo("PENDING");

        // No reviewer is invented. This is the whole reason the backfill is opt-in:
        // a fabricated approval is worse than a visible gap.
        assertThat(stages).allSatisfy(stage -> {
            assertThat(stage[3]).as("decided_by_id").isNull();
            assertThat(stage[4]).as("decided_at").isNull();
        });
    }

    @Test
    @DisplayName("The backfilled request is a normal two-stage request again")
    void backfilledRequestWorksNormally() {
        // The script is only worth anything if the application then behaves as
        // though the stages had always been there: an outstanding stage 1 for a
        // MANAGER, and a workflow that runs to APPROVED.
        Long id = fileStageLessRequest("AWS");
        runBackfill();

        entityManager.clear();

        // The stage 1 the backfill created is a real, outstanding stage, and it
        // takes the whole chain to resolve: MANAGER then IT_ADMIN.
        assertThat(accessRequestService.getRequestById(id, idOf(MANAGER_EMAIL)).nextStage())
                .isNotNull();
        assertThat(accessRequestService.getRequestById(id, idOf(MANAGER_EMAIL)).nextStage().order())
                .isEqualTo(1);

        approveEveryStage(id);

        assertThat(accessRequestService.getRequestById(id, idOf(EMPLOYEE_EMAIL)).status())
                .isEqualTo(AccessRequest.Status.APPROVED);
    }

    @Test
    @DisplayName("Running the backfill a second time changes nothing")
    void backfillIsReRunnable() {
        // A deployer who is not sure whether it has already run - or who runs it
        // twice by accident - must not end up with four stages on one request, and
        // must not get an error either.
        Long id = fileStageLessRequest("AWS");

        runBackfill();
        runBackfill();
        runBackfill();

        assertThat(stagesOf(id)).hasSize(2);
    }

    @Test
    @DisplayName("A request that already has stage 1 is given stage 2 rather than aborting the run")
    void partialRequestIsCompletedNotAborted() {
        // The case a bare INSERT ... SELECT cannot survive: one request already has
        // its stage 1. The constraint rejects the duplicate stage 1, so the whole
        // statement would fail and the pending stage 2 rows would never be
        // inserted - leaving the table no better off than before and the operator
        // with a rollback. The guard skips the request in the first statement and
        // fills in the second one.
        Long partial = fileStageLessRequest("AWS");
        Long untouched = fileStageLessRequest("Grafana");
        insertStageOneOnly(partial);

        assertThat(stagesOf(partial)).hasSize(1);

        runBackfill();

        assertThat(stageOrders(stagesOf(partial))).containsExactly(1, 2);
        assertThat(stageOrders(stagesOf(untouched))).containsExactly(1, 2);
    }

    @Test
    @DisplayName("The backfill never touches a request that is already resolved")
    void resolvedRequestsAreNotTouched() {
        Long approved = createAccessRequest(idOf(EMPLOYEE_EMAIL), "AWS", "already approved");
        Long rejected = createAccessRequest(idOf(EMPLOYEE_EMAIL), "Jira", "already rejected");
        Long withdrawn = createAccessRequest(idOf(EMPLOYEE_EMAIL), "Bitbucket", "already withdrawn");

        markResolved(approved, AccessRequest.Status.APPROVED);
        markResolved(rejected, AccessRequest.Status.REJECTED);
        markResolved(withdrawn, AccessRequest.Status.WITHDRAWN);
        stripStages(approved);
        stripStages(rejected);
        stripStages(withdrawn);

        runBackfill();

        // Still no stages. A resolved request keeps its decision on its own row, and
        // the backfill only ever reads status = 'PENDING'.
        assertThat(stagesOf(approved)).isEmpty();
        assertThat(stagesOf(rejected)).isEmpty();
        assertThat(stagesOf(withdrawn)).isEmpty();
    }

    @Test
    @DisplayName("The README still documents the script these tests run")
    void readmeDocumentsTheScriptThatIsTested() {
        // A documentation change that is not mirrored here would leave the README
        // describing SQL no test has ever executed. This is deliberately a coarse
        // check - it is here to catch divergence, not to parse Markdown.
        String readme = readme();

        assertThat(readme)
                .contains("INSERT INTO access_request_stages (request_id, stage_order, required_role, status)")
                .contains("'MANAGER', 'PENDING'")
                .contains("'IT_ADMIN', 'PENDING'")
                .contains("AND NOT EXISTS (")
                .contains("WHERE s.request_id = r.id AND s.stage_order = 1")
                .contains("WHERE s.request_id = r.id AND s.stage_order = 2")
                .contains("WHERE r.status = 'PENDING'")
                .contains("HAVING stage_count <> 2");
    }

    private void runBackfill() {
        entityManager.createNativeQuery(BACKFILL_STAGE_ONE).executeUpdate();
        entityManager.createNativeQuery(BACKFILL_STAGE_TWO).executeUpdate();
        entityManager.flush();
        entityManager.clear();
    }

    /**
     * Reads the stage rows straight from the table.
     *
     * Through SQL rather than the repository, because the repository is the thing
     * being seeded: what matters here is what a DBA would see in the table, and
     * going through Hibernate would apply a first-level cache that could report a
     * stale or filtered view of exactly the rows under test.
     *
     * The columns come back as {@code [stage_order, required_role, status,
     * decided_by_id, decided_at]}, and the accessors below name them so the
     * assertions read as statements about the workflow rather than as array
     * indexing.
     */
    private List<Object[]> stagesOf(Long requestId) {
        return entityManager.createNativeQuery("""
                        SELECT stage_order, required_role, status, decided_by_id, decided_at
                        FROM access_request_stages
                        WHERE request_id = :id
                        ORDER BY stage_order""")
                .setParameter("id", requestId)
                .getResultList();
    }

    private List<Integer> stageOrders(List<Object[]> stages) {
        return stages.stream().map(this::stageOrder).toList();
    }

    private int stageOrder(Object[] stage) {
        return ((Number) stage[0]).intValue();
    }

    private String requiredRole(Object[] stage) {
        return String.valueOf(stage[1]);
    }

    private String stageStatus(Object[] stage) {
        return String.valueOf(stage[2]);
    }

    private Long fileStageLessRequest(String application) {
        Long id = accessRequestService
                .createRequest(new AccessRequestCreateRequest(application, "filed before the stages existed"),
                        idOf(EMPLOYEE_EMAIL))
                .id();

        stripStages(id);
        return id;
    }

    private void stripStages(Long requestId) {
        AccessRequest request = accessRequestRepository.findById(requestId).orElseThrow();
        request.getStages().clear();
        accessRequestRepository.saveAndFlush(request);
        entityManager.clear();
    }

    private void insertStageOneOnly(Long requestId) {
        entityManager.createNativeQuery("""
                        INSERT INTO access_request_stages (request_id, stage_order, required_role, status)
                        VALUES (:id, 1, 'MANAGER', 'PENDING')""")
                .setParameter("id", requestId)
                .executeUpdate();
        entityManager.flush();
        entityManager.clear();
    }

    private void markResolved(Long requestId, AccessRequest.Status status) {
        User decider = userRepository.findByEmail(MANAGER_EMAIL).orElseThrow();

        AccessRequest request = accessRequestRepository.findById(requestId).orElseThrow();
        request.setStatus(status);
        request.setReviewedBy(decider);
        request.setReviewedAt(java.time.LocalDateTime.now());
        request.setReviewNotes("decided before the stages existed");
        accessRequestRepository.saveAndFlush(request);
        entityManager.clear();
    }

    private String readme() {
        try {
            return new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Path.of("README.md")), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("Could not read README.md", ex);
        }
    }
}
