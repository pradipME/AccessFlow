package com.accessflow.repository;

import java.util.List;

import com.accessflow.entity.AccessRequestStage;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The stage rows of a request.
 *
 * The service reaches the stages through the request it has already loaded,
 * because "the stage to act on next" is a question about one request and a second
 * query would buy nothing. This repository exists so the persisted shape can be
 * asserted directly, independently of the collection the request holds: proving
 * the rows were really written is a different question from asking the entity
 * what it happens to have in memory.
 */
public interface AccessRequestStageRepository extends JpaRepository<AccessRequestStage, Long> {

    /**
     * One request's stages in workflow order.
     *
     * Ordered in the method name rather than left to a Sort, because there is one
     * order this data is ever read in and it has to be total: the workflow's
     * current stage is the lowest stage_order still PENDING, and a tie would make
     * that ambiguous.
     */
    List<AccessRequestStage> findByRequestIdOrderByStageOrderAsc(Long requestId);
}
