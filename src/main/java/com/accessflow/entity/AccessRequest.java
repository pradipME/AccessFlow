package com.accessflow.entity;

import java.time.LocalDateTime;
import java.util.List;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * A request from one employee for access to an application.
 *
 * The target application is a free-text field rather than a reference to a
 * catalogue table: the list of applications is not managed through the API yet,
 * and introducing a catalogue would mean a second entity with its own CRUD for
 * no current benefit.
 *
 * <h2>Workflow</h2>
 *
 * A request starts {@link Status#PENDING} and is resolved exactly once, into
 * {@link Status#APPROVED}, {@link Status#REJECTED} or {@link Status#WITHDRAWN}.
 * Those three are terminal: a decision cannot be revised and a request cannot be
 * reinstated, so a second transition is a conflict.
 *
 * Resolution takes two approvals since Phase 5, and they are held as
 * {@link AccessRequestStage} rows rather than on this table. The request status
 * stays PENDING between them, which is what PENDING has always meant here - not
 * yet resolved - so {@link #isPending()} remains the single gate for both
 * reviewing and withdrawing, and this row gains no stage columns.
 *
 * There is still no event table. The history is derived from this row plus the
 * stage rows, and every one of those facts has exactly one place it is written.
 */
@Entity
@Table(
        name = "access_requests",
        indexes = {
                // The reviewer queue filtered by status: the equality, then the
                // fixed sort. Serves the rows and the page's count query.
                @Index(name = "idx_access_requests_status_created",
                        columnList = "status, created_at, id"),
                // The reviewer queue unfiltered: the fixed sort on its own.
                @Index(name = "idx_access_requests_created",
                        columnList = "created_at, id"),
                // An applicant's own list: applicant equality, then the same sort.
                @Index(name = "idx_access_requests_applicant_created",
                        columnList = "applicant_id, created_at, id")
        })
public class AccessRequest {

    public enum Status {
        PENDING,
        APPROVED,
        REJECTED,

        /**
         * The applicant took the request back before anybody decided it.
         *
         * Terminal, like APPROVED and REJECTED. It is not "cancelled by a
         * reviewer": only the applicant can withdraw, so the applicant stays
         * auditable as the actor without a second actor column.
         */
        WITHDRAWN
    }

    /**
     * Required by JPA. Kept public for the same reason as on User: tests and the
     * mapping layer construct instances from outside this package.
     */
    public AccessRequest() {
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /**
     * LAZY because most reads only need the applicant's id, and
     * spring.jpa.open-in-view=false means a lazy association must be resolved
     * inside the service transaction that maps it to a DTO.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "applicant_id", nullable = false)
    private User applicant;

    @Column(name = "application", nullable = false, length = 100)
    private String application;

    @Column(name = "justification", nullable = false, length = 1000)
    private String justification;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status = Status.PENDING;

    /**
     * The reviewer who resolved the request, or null while it is still pending.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewed_by_id")
    private User reviewedBy;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(name = "review_notes", length = 1000)
    private String reviewNotes;

    /**
     * When the applicant withdrew the request, or null if they never did.
     *
     * Deliberately separate from reviewedAt rather than reusing it: reviewedAt
     * means "a reviewer decided this", and a withdrawn request has no reviewer.
     * Overloading it would make every reader of the row unable to tell a
     * withdrawal from a decision.
     */
    @Column(name = "withdrawn_at")
    private LocalDateTime withdrawnAt;

    /**
     * Why the applicant withdrew, or null. Optional, like approval notes: an
     * applicant withdrawing their own request owes a reviewer no explanation,
     * where a rejection always does.
     */
    @Column(name = "withdrawal_reason", length = 1000)
    private String withdrawalReason;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /**
     * The approval stages, in workflow order.
     *
     * Seeded with one PENDING row per stage when the request is filed, and
     * cascaded so the stages cannot exist without their request. Ordered by
     * stage_order rather than left to the database, because "the stage to act on
     * next" is the whole question the workflow asks and an unordered collection
     * would leave the answer to whichever row the join happened to return first.
     *
     * Batched rather than lazy-one-at-a-time because a paged reviewer queue would
     * otherwise issue one extra query per row on the page to read two short
     * stages; with the batch size at the maximum page size, a page costs one
     * query for the requests and one for their stages.
     *
     * Must be read inside the service transaction that maps it to a DTO.
     */
    @OneToMany(mappedBy = "request", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("stageOrder ASC")
    @BatchSize(size = 100)
    private List<AccessRequestStage> stages = new java.util.ArrayList<>();

    /**
     * Adds a stage to this request, setting the back-reference as well.
     *
     * Called rather than the caller setting both sides, so a stage can never be
     * added with a null request and quietly fail the not-null constraint at
     * flush time with an error that names the table rather than the mistake.
     */
    public void addStage(AccessRequestStage stage) {
        stages.add(stage);
        stage.setRequest(this);
    }

    public List<AccessRequestStage> getStages() {
        return stages;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public User getApplicant() {
        return applicant;
    }

    public void setApplicant(User applicant) {
        this.applicant = applicant;
    }

    public String getApplication() {
        return application;
    }

    public void setApplication(String application) {
        this.application = application;
    }

    public String getJustification() {
        return justification;
    }

    public void setJustification(String justification) {
        this.justification = justification;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public User getReviewedBy() {
        return reviewedBy;
    }

    public void setReviewedBy(User reviewedBy) {
        this.reviewedBy = reviewedBy;
    }

    public LocalDateTime getReviewedAt() {
        return reviewedAt;
    }

    public void setReviewedAt(LocalDateTime reviewedAt) {
        this.reviewedAt = reviewedAt;
    }

    public String getReviewNotes() {
        return reviewNotes;
    }

    public void setReviewNotes(String reviewNotes) {
        this.reviewNotes = reviewNotes;
    }

    public LocalDateTime getWithdrawnAt() {
        return withdrawnAt;
    }

    public void setWithdrawnAt(LocalDateTime withdrawnAt) {
        this.withdrawnAt = withdrawnAt;
    }

    public String getWithdrawalReason() {
        return withdrawalReason;
    }

    public void setWithdrawalReason(String withdrawalReason) {
        this.withdrawalReason = withdrawalReason;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    /**
     * Whether the request is still open for a decision.
     *
     * True only for PENDING, so WITHDRAWN is false here exactly like APPROVED
     * and REJECTED. This is the single test the service uses before any
     * transition, which is what makes all three terminal states behave alike.
     *
     * Since Phase 5 this is also true between the two approval stages, and that
     * is correct rather than a gap: PENDING has always meant "not resolved", and
     * a request waiting for its second approver has not been resolved. It is also
     * why withdrawal stays available after the first approval, which is what the
     * workflow's withdrawal rule asks for.
     */
    public boolean isPending() {
        return status == Status.PENDING;
    }

    /**
     * Identity is the database id only, for the same reason as on User: the
     * business fields are all mutable, so including them would let an instance
     * stop being equal to itself after a status change.
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof AccessRequest)) {
            return false;
        }
        AccessRequest that = (AccessRequest) other;
        return id != null && id.equals(that.getId());
    }

    @Override
    public int hashCode() {
        return AccessRequest.class.hashCode();
    }

    /**
     * Never includes the justification, the review notes or the withdrawal
     * reason: all three are free text that an employee can quote credentials
     * into, and this string reaches logs and exception messages.
     *
     * Only the association identifiers are printed. Calling getId() on a lazy
     * proxy returns the identifier without initialising it, so this stays safe
     * to call outside a transaction, which a full object dump would not.
     */
    @Override
    public String toString() {
        return "AccessRequest{"
                + "id=" + id
                + ", applicantId=" + (applicant == null ? null : applicant.getId())
                + ", application='" + application + '\''
                + ", status=" + status
                + ", reviewedById=" + (reviewedBy == null ? null : reviewedBy.getId())
                + ", reviewedAt=" + reviewedAt
                + ", withdrawnAt=" + withdrawnAt
                + ", createdAt=" + createdAt
                + '}';
    }
}
