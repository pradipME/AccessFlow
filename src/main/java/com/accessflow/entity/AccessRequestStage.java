package com.accessflow.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * One approval stage of a request, and the decision made on it.
 *
 * <h2>Why a table rather than columns on the request</h2>
 *
 * The workflow is two sequential stages, so the alternative - a
 * {@code stage1_status} column, then a {@code stage2_status} column - would have
 * to be rewritten before a third stage could exist, and every reader of the
 * request row would have to know how many stages there are. A row per stage keeps
 * the stage count out of the request, so a stage is one thing with one status, one
 * decider and one timestamp, and the request itself keeps its four existing
 * lifecycle statuses.
 *
 * <h2>What makes it a workflow record and not an event log</h2>
 *
 * A stage exists for the whole life of the request: it is created PENDING when
 * the request is filed and is decided at most once. It is not a log of things
 * that happened, so there is still no second source of truth for a decision -
 * the stage is the only place a stage decision exists, and the request status is
 * derived from the stages rather than stored beside them.
 */
@Entity
@Table(
        name = "access_request_stages",
        // The stage order is meaningful only within a request, and the workflow
        // relies on there being exactly one stage per position. The unique
        // constraint is what stops a second stage 1 being inserted, which would
        // otherwise make "the current stage" ambiguous.
        //
        // It is also the index that stage reads use: they all ask
        // "WHERE request_id = ? ORDER BY stage_order", and this constraint's
        // leftmost column is request_id, so a second index on request_id alone
        // would be redundant.
        uniqueConstraints = @UniqueConstraint(
                name = "uk_access_request_stages_request_order",
                columnNames = {"request_id", "stage_order"}))
public class AccessRequestStage {

    public enum StageStatus {
        /**
         * Not yet decided. Every stage starts here, and only the current stage of
         * a request that is still PENDING can leave it.
         */
        PENDING,
        APPROVED,
        REJECTED
    }

    /**
     * Required by JPA. Public for the same reason as on the other entities: tests
     * and the mapping layer construct instances from outside this package.
     */
    public AccessRequestStage() {
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /**
     * LAZY because the request is normally already in hand, and
     * spring.jpa.open-in-view=false means a lazy association has to be resolved
     * inside the service transaction that maps it to a DTO.
     *
     * Never null: a stage with no request could not be reached, ordered or
     * interpreted, so it is refused at the database as well as here.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "request_id", nullable = false)
    private AccessRequest request;

    /**
     * Which stage this is, counting from 1. The workflow's order.
     *
     * An int rather than an enum because the number of stages is a property of
     * the workflow, not a fixed vocabulary: naming the stages in the schema would
     * mean altering the table to add a third one. The role that may act is on
     * each row instead, so the ordering and the permissions are separate.
     */
    @Column(name = "stage_order", nullable = false)
    private int stageOrder;

    /**
     * The role that may decide this stage.
     *
     * On the row rather than derived from the position, so that reading a stage
     * says who may act on it without the reader having to hold the workflow's
     * definition. If the two ever disagreed, the row is what the service
     * enforces.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "required_role", nullable = false, length = 20)
    private User.Role requiredRole;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private StageStatus status = StageStatus.PENDING;

    /**
     * The reviewer who decided this stage, or null while it is PENDING. Distinct
     * from the request's reviewedBy, which records the reviewer who resolved the
     * request as a whole.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "decided_by_id")
    private User decidedBy;

    /**
     * When the stage was decided, or null while it is PENDING. The service writes
     * it rather than a database default, so the timestamp is the same clock
     * reading as the request's own resolution.
     */
    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    /**
     * The decider's notes. Optional on an approval, mandatory on a rejection -
     * enforced by the request DTOs at the boundary, where a violation is a 400.
     */
    @Column(name = "notes", length = 1000)
    private String notes;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public AccessRequest getRequest() {
        return request;
    }

    public void setRequest(AccessRequest request) {
        this.request = request;
    }

    public int getStageOrder() {
        return stageOrder;
    }

    public void setStageOrder(int stageOrder) {
        this.stageOrder = stageOrder;
    }

    public User.Role getRequiredRole() {
        return requiredRole;
    }

    public void setRequiredRole(User.Role requiredRole) {
        this.requiredRole = requiredRole;
    }

    public StageStatus getStatus() {
        return status;
    }

    public void setStatus(StageStatus status) {
        this.status = status;
    }

    public User getDecidedBy() {
        return decidedBy;
    }

    public void setDecidedBy(User decidedBy) {
        this.decidedBy = decidedBy;
    }

    public LocalDateTime getDecidedAt() {
        return decidedAt;
    }

    public void setDecidedAt(LocalDateTime decidedAt) {
        this.decidedAt = decidedAt;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public boolean isPending() {
        return status == StageStatus.PENDING;
    }

    /**
     * Whether this stage has been decided, which is what the history is derived
     * from. Null-tolerant on decidedAt rather than trusting the status alone: the
     * history reads the timestamp, and a decided stage with no timestamp would
     * otherwise disappear from it.
     */
    public boolean isDecided() {
        return decidedAt != null;
    }

    /**
     * Records a decision on this stage.
     *
     * The stage decides itself rather than having the service assign four fields,
     * so a stage can never be left with a status and a decider that disagree.
     */
    public void decide(User decider, StageStatus decision, String notes, LocalDateTime at) {
        this.status = decision;
        this.decidedBy = decider;
        this.decidedAt = at;
        this.notes = notes;
    }
}
