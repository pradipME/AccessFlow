package com.accessflow.controller;

import java.util.List;

import com.accessflow.dto.AccessRequestApprovalRequest;
import com.accessflow.dto.AccessRequestCreateRequest;
import com.accessflow.dto.AccessRequestRejectionRequest;
import com.accessflow.dto.AccessRequestResponse;
import com.accessflow.dto.AccessRequestWithdrawalRequest;
import com.accessflow.dto.PagedResponse;
import com.accessflow.dto.RequestHistoryEntry;
import com.accessflow.entity.AccessRequest;
import com.accessflow.security.AccessFlowUserDetails;
import com.accessflow.service.AccessRequestService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * REST API for access requests.
 *
 * Talks to AccessRequestService only. The acting user is always read from the
 * authentication, so no endpoint can be used to act as, or submit a request on
 * behalf of, somebody else.
 */
@RestController
@RequestMapping("/api/access-requests")
public class AccessRequestController {

    private final AccessRequestService accessRequestService;

    public AccessRequestController(AccessRequestService accessRequestService) {
        this.accessRequestService = accessRequestService;
    }

    @PostMapping
    public ResponseEntity<AccessRequestResponse> create(
            @Valid @RequestBody AccessRequestCreateRequest request,
            Authentication authentication,
            UriComponentsBuilder uriBuilder) {

        AccessRequestResponse created =
                accessRequestService.createRequest(request, actingUserId(authentication));

        return ResponseEntity
                .created(uriBuilder.path("/api/access-requests/{id}")
                        .buildAndExpand(created.id()).toUri())
                .body(created);
    }

    @GetMapping("/mine")
    public ResponseEntity<List<AccessRequestResponse>> getMine(Authentication authentication) {
        return ResponseEntity.ok(
                accessRequestService.getRequestsByApplicant(actingUserId(authentication)));
    }

    @GetMapping
    public ResponseEntity<List<AccessRequestResponse>> getAll() {
        return ResponseEntity.ok(accessRequestService.getAllRequests());
    }

    @GetMapping("/status/{status}")
    public ResponseEntity<List<AccessRequestResponse>> getByStatus(
            @PathVariable AccessRequest.Status status) {
        return ResponseEntity.ok(accessRequestService.getRequestsByStatus(status));
    }

    @GetMapping("/{id}")
    public ResponseEntity<AccessRequestResponse> getById(@PathVariable Long id,
                                                         Authentication authentication) {
        return ResponseEntity.ok(
                accessRequestService.getRequestById(id, actingUserId(authentication)));
    }

    @PatchMapping("/{id}/approve")
    public ResponseEntity<AccessRequestResponse> approve(
            @PathVariable Long id,
            @Valid @RequestBody(required = false) AccessRequestApprovalRequest request,
            Authentication authentication) {

        String notes = request == null ? null : request.notes();

        return ResponseEntity.ok(
                accessRequestService.approveRequest(id, actingUserId(authentication), notes));
    }

    @PatchMapping("/{id}/reject")
    public ResponseEntity<AccessRequestResponse> reject(
            @PathVariable Long id,
            @Valid @RequestBody AccessRequestRejectionRequest request,
            Authentication authentication) {

        return ResponseEntity.ok(accessRequestService
                .rejectRequest(id, actingUserId(authentication), request.notes()));
    }

    /**
     * The applicant withdraws their own request.
     *
     * The body is optional and carries nothing but a reason. There is no
     * applicant, owner or id field to bind, so the only way to name the person
     * withdrawing is the authentication - a caller cannot withdraw somebody
     * else's request by putting their id anywhere in the request.
     *
     * PATCH, like approve and reject: all three are state transitions on one
     * existing resource rather than the creation of anything.
     */
    @PatchMapping("/{id}/withdraw")
    public ResponseEntity<AccessRequestResponse> withdraw(
            @PathVariable Long id,
            @Valid @RequestBody(required = false) AccessRequestWithdrawalRequest request,
            Authentication authentication) {

        String reason = request == null ? null : request.reason();

        return ResponseEntity.ok(accessRequestService
                .withdrawRequest(id, actingUserId(authentication), reason));
    }

    /**
     * What has happened to one request, oldest first.
     *
     * Available to the applicant and to reviewers, which is the same set that may
     * read the request: the history is not a way around that check.
     */
    @GetMapping("/{id}/history")
    public ResponseEntity<List<RequestHistoryEntry>> history(
            @PathVariable Long id,
            Authentication authentication) {

        return ResponseEntity.ok(
                accessRequestService.getRequestHistory(id, actingUserId(authentication)));
    }

    /**
     * One page of the reviewer queue.
     *
     * New rather than a change to {@code GET /api/access-requests}: that endpoint
     * answers with a list and stays exactly as it was, so no existing client sees
     * a different body. Reviewer-only, enforced by the chain and again in the
     * service.
     *
     * <h2>Why the parameters carry constraints instead of a Pageable</h2>
     *
     * Binding a Pageable would let a client send {@code ?sort=...} and choose the
     * order. It is refused instead, by a constraint that only accepts an empty
     * value: an explicit 400 documents the rule, and a test on it stops a later
     * refactor from quietly re-enabling client-chosen sorting. The order is fixed
     * in the service, and it has to be: without a unique tie-break a paginated
     * query can repeat or skip rows.
     *
     * The constraints are enforced before the method runs, so an out-of-range
     * page or size is a 400 in the same uniform ErrorResponse as any other
     * validation failure, rather than whatever the framework would produce. Both
     * parameters are optional and a missing one means "use the default", so the
     * default lives in one place instead of being spelled out here.
     */
    @GetMapping("/page")
    public ResponseEntity<PagedResponse<AccessRequestResponse>> page(
            @RequestParam(required = false) AccessRequest.Status status,
            @RequestParam(name = "page", required = false) @Min(0) Integer page,
            @RequestParam(name = "size", required = false)
            @Min(1) @Max(AccessRequestService.MAX_PAGE_SIZE) Integer size,
            @RequestParam(name = "sort", required = false)
            @Size(max = 0, message = "sort is not supported: the order is fixed") String sort,
            Authentication authentication) {

        return ResponseEntity.ok(accessRequestService.getRequestsPage(
                actingUserId(authentication), status,
                page == null ? 0 : page,
                size == null ? AccessRequestService.DEFAULT_PAGE_SIZE : size));
    }

    /**
     * Reads the UserDetails off the Authentication token. A Principal parameter
     * would hand back the token itself rather than the UserDetails it wraps.
     *
     * Every route that reaches here requires authentication, so the principal is
     * always a real AccessFlowUserDetails. Anonymous requests are rejected by the
     * filter chain before they can reach this method.
     */
    private Long actingUserId(Authentication authentication) {
        return ((AccessFlowUserDetails) authentication.getPrincipal()).getId();
    }
}
