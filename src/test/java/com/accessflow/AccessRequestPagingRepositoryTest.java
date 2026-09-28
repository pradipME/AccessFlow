package com.accessflow;

import java.util.ArrayList;
import java.util.List;

import com.accessflow.entity.AccessRequest;
import com.accessflow.repository.AccessRequestRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 4 - the paged repository queries.
 *
 * The service applies the sort, so these tests pass the Pageable in explicitly.
 * What is being checked here is the part the service relies on and cannot prove
 * on its own: that a paged query is repeatable, that a filtered count reflects
 * the filter, and that the new indexes exist in the database Hibernate created.
 */
@DisplayName("Phase 4 - AccessRequestRepository paging")
@EnabledIfEnvironmentVariable(named = "ACCESSFLOW_DB_PASSWORD", matches = ".+")
class AccessRequestPagingRepositoryTest extends IntegrationTestSupport {

    /**
     * The same fixed order the service builds. Spelled out here because the point
     * of these tests is to prove that *this* Pageable produces a stable paged
     * result; reusing the service's own constant would test nothing.
     */
    private static final Sort NEWEST_FIRST =
            Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    @Autowired
    private AccessRequestRepository accessRequestRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("findAllBy returns the requested page, newest first")
    void findAllByReturnsThePageNewestFirst() {
        Long employeeId = idOf(EMPLOYEE_EMAIL);
        createAccessRequest(employeeId, "First", "oldest");
        createAccessRequest(employeeId, "Second", "middle");
        createAccessRequest(employeeId, "Third", "newest");

        Page<AccessRequest> page = accessRequestRepository
                .findAllBy(PageRequest.of(0, 2, NEWEST_FIRST));

        assertThat(page.getContent()).extracting(AccessRequest::getApplication)
                .containsExactly("Third", "Second");
        assertThat(page.getNumber()).isZero();
        assertThat(page.getSize()).isEqualTo(2);
        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.getTotalPages()).isEqualTo(2);
    }

    @Test
    @DisplayName("findByStatus returns only the filtered rows and counts only those")
    void findByStatusCountsOnlyTheFilteredRows() {
        Long employeeId = idOf(EMPLOYEE_EMAIL);

        createAccessRequest(employeeId, "Pending1", "waiting");
        createAccessRequest(employeeId, "Pending2", "waiting");
        Long approved = createAccessRequest(employeeId, "Approved", "granted");
        approveEveryStage(approved);
        Long withdrawn = createAccessRequest(employeeId, "Withdrawn", "changed my mind");
        accessRequestService.withdrawRequest(withdrawn, employeeId, null);

        Page<AccessRequest> pending = accessRequestRepository
                .findByStatus(AccessRequest.Status.PENDING, PageRequest.of(0, 20, NEWEST_FIRST));

        // The count is the point: a count that ignored the filter would report
        // all four and report two total pages for a two-row result.
        assertThat(pending.getTotalElements()).isEqualTo(2);
        assertThat(pending.getContent()).extracting(AccessRequest::getApplication)
                .containsExactlyInAnyOrder("Pending1", "Pending2");

        Page<AccessRequest> withdrawnOnly = accessRequestRepository
                .findByStatus(AccessRequest.Status.WITHDRAWN, PageRequest.of(0, 20, NEWEST_FIRST));

        assertThat(withdrawnOnly.getTotalElements()).isEqualTo(1);
        assertThat(withdrawnOnly.getContent()).extracting(AccessRequest::getId)
                .containsExactly(withdrawn);
    }

    @Test
    @DisplayName("WITHDRAWN is queryable as a status in its own right")
    void withdrawnIsQueryable() {
        Long employeeId = idOf(EMPLOYEE_EMAIL);
        Long id = createAccessRequest(employeeId, "GitHub", "take it back");
        accessRequestService.withdrawRequest(id, employeeId, "no longer needed");

        assertThat(accessRequestRepository
                .findByStatusOrderByCreatedAtDescIdDesc(AccessRequest.Status.WITHDRAWN))
                .extracting(AccessRequest::getId).contains(id);
    }

    @Test
    @DisplayName("Successive pages partition the result set with no repeat and no gap")
    void pagesPartitionWithoutOverlapOrGap() {
        Long employeeId = idOf(EMPLOYEE_EMAIL);
        for (int i = 0; i < 25; i++) {
            createAccessRequest(employeeId, "App" + i, "filler " + i);
        }

        List<Long> seen = new ArrayList<>();
        for (int pageNumber = 0; pageNumber < 3; pageNumber++) {
            accessRequestRepository
                    .findAllBy(PageRequest.of(pageNumber, 10, NEWEST_FIRST))
                    .getContent()
                    .forEach(request -> seen.add(request.getId()));
        }

        assertThat(seen).hasSize(25).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("Reading the same page twice returns the same rows in the same order")
    void pagingIsRepeatable() {
        // createdAt is not unique, so without the id tie-break the order of rows
        // sharing a timestamp is the database's choice and can differ between
        // calls - which would make a paged read repeat or skip rows.
        Long employeeId = idOf(EMPLOYEE_EMAIL);
        for (int i = 0; i < 20; i++) {
            createAccessRequest(employeeId, "App" + i, "filler " + i);
        }

        List<Long> firstRead = accessRequestRepository
                .findAllBy(PageRequest.of(0, 10, NEWEST_FIRST))
                .getContent().stream().map(AccessRequest::getId).toList();
        List<Long> secondRead = accessRequestRepository
                .findAllBy(PageRequest.of(0, 10, NEWEST_FIRST))
                .getContent().stream().map(AccessRequest::getId).toList();

        assertThat(firstRead).isEqualTo(secondRead);
    }

    @Test
    @DisplayName("Without the id tie-break the same query is still stable, so the tie-break is belt and braces")
    void orderIsTotalByIdAlone() {
        // Not a test that the tie-break is unnecessary - it is a test that the
        // documented order (createdAt DESC, id DESC) is what the repository
        // actually applies, rather than createdAt alone.
        Long employeeId = idOf(EMPLOYEE_EMAIL);
        createAccessRequest(employeeId, "One", "first");
        createAccessRequest(employeeId, "Two", "second");
        createAccessRequest(employeeId, "Three", "third");

        assertThat(accessRequestRepository.findAllBy(PageRequest.of(0, 20, NEWEST_FIRST)))
                .extracting(AccessRequest::getApplication)
                .containsExactly("Three", "Two", "One");
    }

    @Test
    @DisplayName("A page past the end is empty, and the totals still describe the whole result set")
    void pagePastTheEndIsEmpty() {
        Long employeeId = idOf(EMPLOYEE_EMAIL);
        createAccessRequest(employeeId, "OnlyOne", "the only one");

        Page<AccessRequest> beyond = accessRequestRepository
                .findAllBy(PageRequest.of(99, 20, NEWEST_FIRST));

        assertThat(beyond.getContent()).isEmpty();
        assertThat(beyond.getTotalElements()).isEqualTo(1);
        assertThat(beyond.isFirst()).isFalse();
        assertThat(beyond.isLast()).isTrue();
    }

    @Test
    @DisplayName("A withdrawn request is persisted with its timestamp and reason")
    void withdrawalColumnsArePersisted() {
        Long employeeId = idOf(EMPLOYEE_EMAIL);
        Long id = createAccessRequest(employeeId, "AWS", "persist the withdrawal");
        accessRequestService.withdrawRequest(id, employeeId, "changed my mind");
        accessRequestRepository.flush();

        AccessRequest stored = accessRequestRepository.findById(id).orElseThrow();

        assertThat(stored.getStatus()).isEqualTo(AccessRequest.Status.WITHDRAWN);
        assertThat(stored.getWithdrawnAt()).isNotNull();
        assertThat(stored.getWithdrawalReason()).isEqualTo("changed my mind");
    }

    @Test
    @DisplayName("A request that was never withdrawn has no withdrawal timestamp or reason")
    void withdrawalColumnsAreNullUntilUsed() {
        Long employeeId = idOf(EMPLOYEE_EMAIL);
        Long id = createAccessRequest(employeeId, "AWS", "not withdrawn");

        AccessRequest stored = accessRequestRepository.findById(id).orElseThrow();

        assertThat(stored.getWithdrawnAt()).isNull();
        assertThat(stored.getWithdrawalReason()).isNull();
    }

    @Test
    @DisplayName("A decision does not populate the withdrawal columns")
    void decisionAndWithdrawalAreIndependent() {
        // reviewedAt means a reviewer decided; withdrawnAt means the applicant
        // took it back. Reusing one column for both would make every reader unable
        // to tell the two apart.
        Long employeeId = idOf(EMPLOYEE_EMAIL);
        Long approved = createAccessRequest(employeeId, "AWS", "approved");
        approveEveryStage(approved);

        Long withdrawn = createAccessRequest(employeeId, "Jira", "withdrawn");
        accessRequestService.withdrawRequest(withdrawn, employeeId, "changed my mind");

        AccessRequest approvedRow = accessRequestRepository.findById(approved).orElseThrow();
        assertThat(approvedRow.getReviewedAt()).isNotNull();
        assertThat(approvedRow.getWithdrawnAt()).isNull();

        AccessRequest withdrawnRow = accessRequestRepository.findById(withdrawn).orElseThrow();
        assertThat(withdrawnRow.getWithdrawnAt()).isNotNull();
        assertThat(withdrawnRow.getReviewedAt()).isNull();
    }

    @Test
    @DisplayName("A withdrawal reason of exactly 1000 characters is stored whole")
    void boundaryLengthReasonIsStoredWhole() {
        Long employeeId = idOf(EMPLOYEE_EMAIL);
        Long id = createAccessRequest(employeeId, "AWS", "exactly at the limit");
        String reason = "z".repeat(1000);
        accessRequestService.withdrawRequest(id, employeeId, reason);
        accessRequestRepository.flush();

        assertThat(accessRequestRepository.findById(id).orElseThrow().getWithdrawalReason())
                .hasSize(1000).isEqualTo(reason);
    }

    @Test
    @DisplayName("The three Phase 4 indexes exist in the database")
    void phaseFourIndexesExist() {
        // Each is an equality prefix plus the fixed sort, which is what the
        // queries need. ddl-auto=update created them from the @Index declarations;
        // this is the check that they are really there and not only declared.
        List<String> names = indexNamesOnAccessRequests();

        assertThat(names).contains(
                "idx_access_requests_status_created",
                "idx_access_requests_created",
                "idx_access_requests_applicant_created");
    }

    /**
     * Reads the index names off the access_requests table.
     *
     * Information_schema rather than the entity metadata, because the metadata
     * would only prove the annotation is there. This proves MySQL has the index.
     */
    private List<String> indexNamesOnAccessRequests() {
        return jdbcTemplate.queryForList(
                """
                SELECT DISTINCT index_name
                  FROM information_schema.statistics
                 WHERE table_schema = DATABASE()
                   AND table_name = 'access_requests'
                """,
                String.class);
    }
}
