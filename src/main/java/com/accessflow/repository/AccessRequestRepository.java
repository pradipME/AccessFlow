package com.accessflow.repository;

import java.util.List;

import com.accessflow.entity.AccessRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AccessRequestRepository extends JpaRepository<AccessRequest, Long> {

    /**
     * Newest first, which is the order an employee and a reviewer both read
     * their queues in.
     *
     * The generated timestamp is not unique: several requests created in the
     * same clock tick share a value, and the database is then free to return
     * those rows in any order. The auto-increment id breaks the tie and is
     * monotonic, so the newest request always comes first and the result is
     * stable across calls. Without it a paginated queue could repeat or skip
     * rows.
     */
    List<AccessRequest> findByApplicantIdOrderByCreatedAtDescIdDesc(Long applicantId);

    List<AccessRequest> findByStatusOrderByCreatedAtDescIdDesc(AccessRequest.Status status);

    /**
     * The full reviewer queue. findAll() would leave the order undefined, so
     * the reviewer's list would not match the ordering of the two queries above.
     */
    List<AccessRequest> findAllByOrderByCreatedAtDescIdDesc();

    /**
     * Paged forms of the two reviewer queries.
     *
     * Deliberately no {@code OrderBy} in the method name: the order comes from
     * the Pageable the service builds, which is fixed in one place. Naming the
     * sort here as well would leave two places to keep in step, and Spring Data
     * would then combine both orderings instead of choosing one.
     *
     * Each has a matching count query, which Spring Data derives from the same
     * predicate - so paging a filtered queue counts only the filtered rows
     * rather than the whole table.
     */
    Page<AccessRequest> findAllBy(Pageable pageable);

    Page<AccessRequest> findByStatus(AccessRequest.Status status, Pageable pageable);
}
