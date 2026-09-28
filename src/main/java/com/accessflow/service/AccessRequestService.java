package com.accessflow.service;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import com.accessflow.dto.AccessRequestCreateRequest;
import com.accessflow.dto.AccessRequestResponse;
import com.accessflow.dto.PagedResponse;
import com.accessflow.dto.RequestHistoryEntry;
import com.accessflow.entity.AccessRequest;
import com.accessflow.entity.AccessRequestStage;
import com.accessflow.entity.User;
import com.accessflow.exception.AccessRequestNotFoundException;
import com.accessflow.exception.InsufficientRoleException;
import com.accessflow.exception.InvalidAccessRequestStateException;
import com.accessflow.exception.SelfApprovalNotAllowedException;
import com.accessflow.exception.UserNotFoundException;
import com.accessflow.repository.AccessRequestRepository;
import com.accessflow.repository.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Access request workflow.
 *
 * <h2>Two sequential approvals</h2>
 *
 * Since Phase 5 a request is resolved by two approvals in order: a MANAGER at
 * stage 1 and an IT_ADMIN at stage 2. Both are required. The request status
 * stays PENDING until the second one lands, so {@code isPending()} is still the
 * single gate in front of every transition and APPROVED/REJECTED/WITHDRAWN are
 * still the only terminal states - the request table gained no stage columns, and
 * {@code status} was not widened.
 *
 * A rejection at either stage terminates the request immediately; there is no
 * state in which a rejected request can be picked up again.
 *
 * The stage definitions live in {@link #APPROVAL_STAGES} and are copied into
 * {@link AccessRequestStage} rows when a request is filed, so the workflow is
 * recorded per request rather than looked up on every read. A stage records which
 * role may decide it, and the service enforces that role.
 *
 * <h2>Reading</h2>
 *
 * Every read resolves the lazy associations inside the transaction by mapping to
 * {@link AccessRequestResponse} here rather than returning entities, because
 * spring.jpa.open-in-view=false means a proxy touched after the transaction
 * closes would fail.
 */
@Service
@Transactional(readOnly = true)
public class AccessRequestService {

    /**
     * Roles allowed to review access requests. Mirrors the filter chain, which is
     * the first gate; this set is the second one.
     */
    private static final Set<User.Role> REVIEWER_ROLES = EnumSet.of(
            User.Role.MANAGER, User.Role.IT_ADMIN, User.Role.SUPER_ADMIN);

    /**
     * The approval chain, in order: the role that may decide each stage.
     *
     * The single definition of the workflow. A request copies these into stage
     * rows when it is filed, so a later change here does not rewrite requests
     * that are already part-way through: an in-flight request keeps the chain it
     * was filed under. That is deliberate - changing the chain halfway through a
     * pending request would leave it approvable by nobody or by two people at
     * once, depending on which side of the change the reader was standing on.
     *
     * Exposed so the tests and the documentation can assert the chain without
     * restating it.
     */
    public static final List<User.Role> APPROVAL_STAGES = List.of(
            User.Role.MANAGER, User.Role.IT_ADMIN);

    /**
     * Whether a role may decide a stage that asks for {@code requiredRole}.
     *
     * A SUPER_ADMIN may act at any stage. It is the one role that already could
     * review every request before Phase 5, and taking that away would be a
     * silent reduction in what an administrator can do rather than a new rule.
     * Everyone else is limited to the stage that names their role, so a MANAGER
     * cannot approve stage 2 and an IT_ADMIN cannot approve stage 1.
     *
     * Null-tolerant in both arguments: an unauthenticated caller has no role, and
     * a stage with no required role is decided by nobody rather than by everybody.
     *
     * Public for the same reason as {@link #isReviewerRole}: the pages use it to
     * decide what to draw, and a second copy of the set here could drift from
     * this one. The answer only decides what is shown; the service re-checks it
     * on every decision, so hiding a button is never what enforces the policy.
     */
    public static boolean isStageReviewer(User.Role role, User.Role requiredRole) {
        if (role == null || requiredRole == null) {
            return false;
        }
        return role == User.Role.SUPER_ADMIN || role == requiredRole;
    }

    /**
     * Page size used when a caller does not ask for one.
     */
    public static final int DEFAULT_PAGE_SIZE = 20;

    /**
     * Largest page a caller may ask for. Enforced at the request boundary and
     * relied on here: an unbounded page size is an unbounded result set.
     */
    public static final int MAX_PAGE_SIZE = 100;

    /**
     * The one order every listing is read in: newest first, with the id breaking
     * ties.
     *
     * Fixed here rather than taken from the caller, and applied to the Pageable
     * rather than named in the repository methods, so there is exactly one
     * definition and no way to reach a different one. The id tie-breaker is not
     * cosmetic: createdAt is not unique, and without it a row whose timestamp
     * collides with another could be returned on two pages or on none.
     */
    private static final Sort NEWEST_FIRST =
            Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    /**
     * Whether a role may review access requests.
     *
     * Exposed so the Thymeleaf layer can decide which links to render without
     * keeping a second copy of the set that could drift away from this one. The
     * answer only decides what is drawn on a page; every rule below still refuses
     * an unauthorised caller, so hiding a link is never what enforces the policy.
     *
     * Null-tolerant on purpose: an unauthenticated caller has no role, and a null
     * is not a reviewer.
     */
    public static boolean isReviewerRole(User.Role role) {
        return role != null && REVIEWER_ROLES.contains(role);
    }

    private final AccessRequestRepository accessRequestRepository;
    private final UserRepository userRepository;

    public AccessRequestService(AccessRequestRepository accessRequestRepository,
                                UserRepository userRepository) {
        this.accessRequestRepository = accessRequestRepository;
        this.userRepository = userRepository;
    }

    /**
     * The applicant comes from the authentication, never from the request body,
     * and arrives as an id so no throwaway entity has to be built. It is
     * re-read from the repository so the foreign key is written from a managed
     * instance, and so a user deleted mid-session fails loudly instead of
     * silently orphaning the request.
     */
    @Transactional
    public AccessRequestResponse createRequest(AccessRequestCreateRequest request, Long applicantId) {
        User applicant = requireUser(applicantId);

        AccessRequest entity = new AccessRequest();
        entity.setApplicant(applicant);
        entity.setApplication(request.application());
        entity.setJustification(request.justification());
        entity.setStatus(AccessRequest.Status.PENDING);

        // The chain is copied onto the request now rather than looked up on every
        // read, so the stages a request is being reviewed against are fixed when it
        // is filed. addStage sets the back-reference, and the cascade persists them
        // with the request in one flush.
        for (int i = 0; i < APPROVAL_STAGES.size(); i++) {
            AccessRequestStage stage = new AccessRequestStage();
            stage.setStageOrder(i + 1);
            stage.setRequiredRole(APPROVAL_STAGES.get(i));
            stage.setStatus(AccessRequestStage.StageStatus.PENDING);
            entity.addStage(stage);
        }

        return AccessRequestResponse.from(accessRequestRepository.save(entity));
    }

    public AccessRequestResponse getRequestById(Long id, Long callerId) {
        AccessRequest request = requireRequest(id);
        requireReadable(request, callerId);

        return AccessRequestResponse.from(request);
    }

    /**
     * Who may read a request: the applicant, or a reviewer.
     *
     * The justification is free text the applicant wrote and is not meant to be
     * company-wide, so an employee asking for somebody else's request is refused
     * rather than served a filtered version. A 403 rather than a 404, so the
     * caller can tell "not allowed" from "no such request" - the same trade-off
     * the existing read rule already makes.
     *
     * Shared by the request itself and its history: history is the request's
     * other half, and it must not become a way around this check.
     */
    private void requireReadable(AccessRequest request, Long callerId) {
        if (isApplicant(request, callerId)) {
            return;
        }

        User caller = requireUser(callerId);
        if (!isReviewerRole(caller.getRole())) {
            throw new InsufficientRoleException(caller.getRole(), "read another employee's access request");
        }
    }

    /**
     * The history of one request, oldest first.
     *
     * Read through the same rule as the request, and derived from the row rather
     * than read from a table: see {@link RequestHistoryEntry} for why there is
     * no event log.
     */
    public List<RequestHistoryEntry> getRequestHistory(Long id, Long callerId) {
        AccessRequest request = requireRequest(id);
        requireReadable(request, callerId);

        return RequestHistoryEntry.of(request);
    }

    public List<AccessRequestResponse> getRequestsByApplicant(Long applicantId) {
        return accessRequestRepository.findByApplicantIdOrderByCreatedAtDescIdDesc(applicantId).stream()
                .map(AccessRequestResponse::from)
                .toList();
    }

    public List<AccessRequestResponse> getRequestsByStatus(AccessRequest.Status status) {
        return accessRequestRepository.findByStatusOrderByCreatedAtDescIdDesc(status).stream()
                .map(AccessRequestResponse::from)
                .toList();
    }

    public List<AccessRequestResponse> getAllRequests() {
        return accessRequestRepository.findAllByOrderByCreatedAtDescIdDesc().stream()
                .map(AccessRequestResponse::from)
                .toList();
    }

    /**
     * One page of the reviewer queue, newest first, optionally narrowed to a
     * status.
     *
     * Reviewer-only, checked here as well as in the filter chain: the chain is
     * the first gate and this is the second, so a caller that reaches the service
     * by any other route is still refused.
     *
     * The sort is built here and cannot be supplied by the caller. The order is
     * what makes paging correct - with a non-unique timestamp and no tie-break,
     * a row can be returned on two pages or on none - so it has to be the same
     * order every time, not one the client picks.
     *
     * The page and size are validated at the request boundary, which is the only
     * place that can answer a client with a 400. Values arriving here from
     * anywhere else are out of contract, and PageRequest's own assertions are
     * what stops them.
     */
    public PagedResponse<AccessRequestResponse> getRequestsPage(Long reviewerId, AccessRequest.Status status,
                                                                int page, int size) {
        User reviewer = requireUser(reviewerId);
        if (!isReviewerRole(reviewer.getRole())) {
            throw new InsufficientRoleException(reviewer.getRole(), "browse the review queue");
        }

        PageRequest pageable = PageRequest.of(page, size, NEWEST_FIRST);

        Page<AccessRequest> found = status == null
                ? accessRequestRepository.findAllBy(pageable)
                : accessRequestRepository.findByStatus(status, pageable);

        // Mapped here, inside the transaction, for the same reason every other
        // read is: the applicant and reviewer associations are lazy.
        return PagedResponse.of(found.map(AccessRequestResponse::from));
    }

    @Transactional
    public AccessRequestResponse approveRequest(Long id, Long reviewerId, String notes) {
        return review(id, reviewerId, notes, true);
    }

    @Transactional
    public AccessRequestResponse rejectRequest(Long id, Long reviewerId, String notes) {
        return review(id, reviewerId, notes, false);
    }

    /**
     * The applicant takes back their own request before anybody decides it.
     *
     * Available to every role, because a MANAGER filing a request may change
     * their mind like anyone else, and refused to every role for somebody
     * else's: a reviewer already has approve and reject, which is the stronger
     * action and one that can explain itself. A note-free way for a reviewer to
     * close another person's request would leave the reviewer nothing to write
     * and the applicant nothing to read.
     *
     * The three checks are in this order on purpose. Existence first, then
     * ownership, then state - so somebody who does not own a request is told
     * only that it is not theirs, and cannot walk a request id through a
     * sequence of 403s and 409s to learn whether it is still open. That is the
     * one piece of information a non-owner must not be able to discover.
     */
    @Transactional
    public AccessRequestResponse withdrawRequest(Long id, Long callerId, String reason) {
        AccessRequest request = requireRequest(id);

        if (!isApplicant(request, callerId)) {
            User caller = requireUser(callerId);
            throw new InsufficientRoleException(caller.getRole(),
                    "withdraw another employee's access request");
        }

        if (!request.isPending()) {
            throw new InvalidAccessRequestStateException(request.getStatus(), "withdraw");
        }

        request.setStatus(AccessRequest.Status.WITHDRAWN);
        request.setWithdrawnAt(LocalDateTime.now());
        request.setWithdrawalReason(reason);

        // reviewedBy, reviewedAt and reviewNotes are deliberately left alone:
        // null already, because a request can only be reviewed while it is
        // pending, and writing them here would put a reviewer on a withdrawal
        // that had none.
        return AccessRequestResponse.from(request);
    }

    /**
     * Approve and reject differ only in what they do to the stage and to the
     * request, so the shared rules live in one place: the request must still be
     * pending, the caller must not be the applicant, and the caller must hold the
     * role the current stage asks for. Whether notes are mandatory is enforced by
     * the request DTOs at the API boundary, where a violation is a 400 rather
     * than a 500.
     *
     * <h2>The order the checks run in</h2>
     *
     * 404, then 409 while not pending, then 403 for self-approval, then 403 for
     * the stage role. The same order as the single-stage workflow, and the
     * self-approval check stays ahead of the role check so an applicant who is
     * also a reviewer is told they cannot approve their own request rather than
     * being told they hold the wrong role for the stage.
     *
     * <h2>What an approval means now</h2>
     *
     * An approval decides one stage. Only the approval of the last outstanding
     * stage resolves the request as APPROVED; before that the request stays
     * PENDING, because it has not been resolved yet. A rejection resolves it
     * immediately at whichever stage it lands, which is why a rejected request
     * never has a second stage to act on.
     */
    private AccessRequestResponse review(Long id, Long reviewerId, String notes, boolean approve) {
        String verb = approve ? "approve" : "reject";
        AccessRequest request = requireRequest(id);

        if (!request.isPending()) {
            throw new InvalidAccessRequestStateException(request.getStatus(), verb);
        }

        AccessRequestStage stage = requireCurrentStage(request, verb);

        if (request.getApplicant() != null
                && request.getApplicant().getId().equals(reviewerId)) {
            throw new SelfApprovalNotAllowedException();
        }

        User reviewer = requireUser(reviewerId);
        if (!isStageReviewer(reviewer.getRole(), stage.getRequiredRole())) {
            throw new InsufficientRoleException(reviewer.getRole(),
                    verb + " stage " + stage.getStageOrder() + ", which requires "
                            + stage.getRequiredRole());
        }

        LocalDateTime now = LocalDateTime.now();
        stage.decide(reviewer,
                approve ? AccessRequestStage.StageStatus.APPROVED
                        : AccessRequestStage.StageStatus.REJECTED,
                notes, now);

        if (!approve) {
            request.setStatus(AccessRequest.Status.REJECTED);
        } else if (allStagesApproved(request)) {
            request.setStatus(AccessRequest.Status.APPROVED);
        }

        // reviewedBy, reviewedAt and reviewNotes record the reviewer who resolved
        // the request as a whole, so they are written only once the request is no
        // longer PENDING. A first-stage approval leaves them null, which is what
        // keeps "a reviewer decided this" true of them.
        if (!request.isPending()) {
            request.setReviewedBy(reviewer);
            request.setReviewedAt(now);
            request.setReviewNotes(notes);
        }

        return AccessRequestResponse.from(request);
    }

    /**
     * The stage a decision applies to: the earliest one still PENDING.
     *
     * Refused as a conflict rather than assumed, because the workflow has no state
     * in which a PENDING request has no outstanding stage. Reporting it is better
     * than letting a null through into the role check, where it would be answered
     * as "you hold the wrong role" for a request that is really in a state the
     * workflow does not define.
     */
    private AccessRequestStage requireCurrentStage(AccessRequest request, String verb) {
        return request.getStages().stream()
                .filter(AccessRequestStage::isPending)
                .findFirst()
                .orElseThrow(() -> new InvalidAccessRequestStateException(
                        request.getStatus(), verb + " a request with no outstanding stage"));
    }

    /**
     * Whether every stage of the request has been approved, which is the condition
     * for resolving it as APPROVED. A request with no stages at all would vacuously
     * satisfy this, so the emptiness is refused rather than approved by default.
     */
    private boolean allStagesApproved(AccessRequest request) {
        return !request.getStages().isEmpty()
                && request.getStages().stream()
                        .allMatch(stage -> stage.getStatus() == AccessRequestStage.StageStatus.APPROVED);
    }

    private AccessRequest requireRequest(Long id) {
        return accessRequestRepository.findById(id)
                .orElseThrow(() -> new AccessRequestNotFoundException(id));
    }

    /**
     * Whether the caller is the applicant. Null-tolerant: a caller with no
     * principal is nobody's applicant, and an applicant association that is
     * somehow absent means the request is nobody's.
     */
    private boolean isApplicant(AccessRequest request, Long callerId) {
        return callerId != null
                && request.getApplicant() != null
                && request.getApplicant().getId().equals(callerId);
    }

    private User requireUser(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new UserNotFoundException("id " + id));
    }
}
