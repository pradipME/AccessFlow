package com.accessflow.web;

import com.accessflow.dto.AccessRequestApprovalRequest;
import com.accessflow.dto.AccessRequestCreateRequest;
import com.accessflow.dto.AccessRequestRejectionRequest;
import com.accessflow.dto.AccessRequestResponse;
import com.accessflow.dto.AccessRequestStageResponse;
import com.accessflow.dto.AccessRequestWithdrawalRequest;
import com.accessflow.dto.PagedResponse;
import com.accessflow.entity.AccessRequest;
import com.accessflow.exception.InsufficientRoleException;
import com.accessflow.security.AccessFlowUserDetails;
import com.accessflow.service.AccessRequestService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The browser view of the access request workflow.
 *
 * Every action is a form post that delegates to {@link AccessRequestService} and
 * then redirects, so the rules, the transitions and the exceptions are the ones
 * the API already enforces: the pages add no second implementation of the
 * workflow that could drift away from it. A refusal raised by the service arrives
 * here as an error page through WebViewAdvice.
 *
 * The acting user always comes from the authentication. No form carries an
 * applicant, an owner or a reviewer id, so there is nothing for a caller to
 * change and no way to file a request as somebody else.
 *
 * Two request DTOs are reused as the form-backing objects rather than
 * duplicated as web forms: the annotations on them are the same validation the
 * API applies, so the two entry points cannot disagree about what a valid
 * application or a valid justification is.
 */
@Controller
public class AccessRequestWebController {

    private final AccessRequestService accessRequestService;

    public AccessRequestWebController(AccessRequestService accessRequestService) {
        this.accessRequestService = accessRequestService;
    }

    @GetMapping("/requests/new")
    public String newRequestForm(Model model) {
        if (!model.containsAttribute("accessRequestForm")) {
            model.addAttribute("accessRequestForm", new AccessRequestCreateRequest("", ""));
        }
        return "requests/new";
    }

    @PostMapping("/requests/new")
    public String submitRequest(@Valid @ModelAttribute("accessRequestForm") AccessRequestCreateRequest form,
                                BindingResult bindingResult,
                                Authentication authentication) {

        if (bindingResult.hasErrors()) {
            // Re-render with the messages and what the applicant typed. Nothing has
            // been written: the service was never called.
            return "requests/new";
        }

        AccessRequestResponse created =
                accessRequestService.createRequest(form, actingUser(authentication).getId());

        return "redirect:/requests/" + created.id();
    }

    /**
     * The caller's own requests. The applicant is read from the authentication,
     * so this list can only ever be the caller's own.
     */
    @GetMapping("/requests")
    public String myRequests(Authentication authentication, Model model) {
        model.addAttribute("accessRequests",
                accessRequestService.getRequestsByApplicant(actingUser(authentication).getId()));
        return "requests/mine";
    }

    /**
     * The detail page. Reading it is decided by AccessRequestService, which
     * refuses an employee who is not the applicant; the page is only reached once
     * that check has passed.
     */
    @GetMapping("/requests/{id:\\d+}")
    public String requestDetail(@PathVariable Long id, Authentication authentication, Model model) {
        addDetailModel(id, authentication, model);
        return "requests/detail";
    }

    /**
     * The review queue, one page at a time, optionally narrowed to one status.
     *
     * The role is checked here as well as in the filter chain: the chain is the
     * first gate, and a page that leaked the queue to an employee would not be
     * caught by the service, which has no rule about listing.
     *
     * <h2>Why the page is constrained rather than clamped</h2>
     *
     * The two paging arguments are validated by the same bounds the API uses, and
     * a value outside them becomes a 400 page. Clamping instead - a size of 1000
     * quietly becoming 100 - would answer a question the reviewer did not ask and
     * hide a bug in whatever is calling the page, and with it the truncation that
     * would otherwise be obvious.
     *
     * A page past the end is not an error: it renders empty, with the previous
     * page available, which is what a stale bookmark should do.
     */
    @GetMapping("/requests/review")
    public String reviewQueue(@RequestParam(required = false) AccessRequest.Status status,
                              @RequestParam(name = "page", required = false) @Min(0) Integer page,
                              @RequestParam(name = "size", required = false)
                              @Min(1) @Max(AccessRequestService.MAX_PAGE_SIZE) Integer size,
                              Authentication authentication,
                              Model model) {

        AccessFlowUserDetails viewer = actingUser(authentication);
        if (!AccessRequestService.isReviewerRole(viewer.getRole())) {
            throw new InsufficientRoleException(viewer.getRole(), "browse the review queue");
        }

        PagedResponse<AccessRequestResponse> result = accessRequestService.getRequestsPage(
                viewer.getId(), status,
                page == null ? 0 : page,
                size == null ? AccessRequestService.DEFAULT_PAGE_SIZE : size);

        model.addAttribute("page", result);
        model.addAttribute("selectedStatus", status);
        model.addAttribute("statuses", AccessRequest.Status.values());

        return "requests/review";
    }

    @PostMapping("/requests/{id:\\d+}/approve")
    public String approve(@PathVariable Long id,
                          @Valid @ModelAttribute("approvalForm") AccessRequestApprovalRequest form,
                          BindingResult bindingResult,
                          Authentication authentication,
                          Model model) {

        if (bindingResult.hasErrors()) {
            addDetailModel(id, authentication, model);
            return "requests/detail";
        }

        accessRequestService.approveRequest(id, actingUser(authentication).getId(), form.notes());

        return "redirect:/requests/" + id;
    }

    @PostMapping("/requests/{id:\\d+}/reject")
    public String reject(@PathVariable Long id,
                         @Valid @ModelAttribute("rejectionForm") AccessRequestRejectionRequest form,
                         BindingResult bindingResult,
                         Authentication authentication,
                         Model model) {

        if (bindingResult.hasErrors()) {
            // A rejection always needs a reason. The rule lives on the DTO, and
            // the form obeys it, so a blank reason is refused here and the request
            // is still PENDING.
            addDetailModel(id, authentication, model);
            return "requests/detail";
        }

        accessRequestService.rejectRequest(id, actingUser(authentication).getId(), form.notes());

        return "redirect:/requests/" + id;
    }

    /**
     * The applicant withdraws their own request.
     *
     * The form carries a reason and nothing else - no applicant, no owner, no id
     * to aim it at. The person withdrawing is whoever is signed in, so a form
     * cannot be edited to withdraw somebody else's request; the service refuses
     * that regardless of the role, since a reviewer already has approve and
     * reject.
     *
     * The reason is optional, so the only way this re-renders is a reason over the
     * 1000-character limit.
     */
    @PostMapping("/requests/{id:\\d+}/withdraw")
    public String withdraw(@PathVariable Long id,
                           @Valid @ModelAttribute("withdrawalForm") AccessRequestWithdrawalRequest form,
                           BindingResult bindingResult,
                           Authentication authentication,
                           Model model) {

        if (bindingResult.hasErrors()) {
            addDetailModel(id, authentication, model);
            return "requests/detail";
        }

        accessRequestService.withdrawRequest(id, actingUser(authentication).getId(), form.reason());

        return "redirect:/requests/" + id;
    }

    /**
     * Everything the detail page needs, re-read through the service so that the
     * read authorisation is re-checked on a validation re-render rather than
     * assumed from the page the reviewer came from.
     *
     * The history goes through the same service call, so it obeys the same read
     * rule as the request it belongs to and cannot be reached around it.
     *
     * The three review forms are only seeded when the request does not already
     * carry them, otherwise a rejected submission would lose the text the
     * reviewer typed.
     */
    private void addDetailModel(Long id, Authentication authentication, Model model) {
        AccessFlowUserDetails viewer = actingUser(authentication);
        AccessRequestResponse accessRequest = accessRequestService.getRequestById(id, viewer.getId());

        boolean isApplicant = accessRequest.applicant() != null
                && accessRequest.applicant().id().equals(viewer.getId());

        model.addAttribute("accessRequest", accessRequest);
        model.addAttribute("isApplicant", isApplicant);

        // The stage the viewer would be deciding, and whether that is them. The
        // page shows the review forms only when the viewer's role is the one the
        // current stage asks for: a MANAGER looking at a request whose next stage
        // is IT_ADMIN's sees the progress and not the buttons, because their
        // approval would be refused.
        //
        // Presentation only. The POST handlers re-check the stage, the role and
        // self-approval through AccessRequestService, so hiding a button is never
        // what enforces the rule.
        AccessRequestStageResponse currentStage = accessRequest.nextStage();
        model.addAttribute("currentStage", currentStage);
        model.addAttribute("canReview", currentStage != null
                && !isApplicant
                && AccessRequestService.isStageReviewer(viewer.getRole(), currentStage.requiredRole()));

        // The applicant alone may withdraw, and only while the request is still
        // PENDING. A reviewer looking at somebody else's request never sees the
        // form, so the page does not offer an action the service would refuse.
        model.addAttribute("canWithdraw", isApplicant
                && accessRequest.status() == AccessRequest.Status.PENDING);

        model.addAttribute("history",
                accessRequestService.getRequestHistory(id, viewer.getId()));

        if (!model.containsAttribute("approvalForm")) {
            model.addAttribute("approvalForm", new AccessRequestApprovalRequest(null));
        }
        if (!model.containsAttribute("rejectionForm")) {
            model.addAttribute("rejectionForm", new AccessRequestRejectionRequest(null));
        }
        if (!model.containsAttribute("withdrawalForm")) {
            model.addAttribute("withdrawalForm", new AccessRequestWithdrawalRequest(null));
        }
    }

    /**
     * Reads the UserDetails off the Authentication token. A Principal parameter
     * would hand back the token itself rather than the UserDetails it wraps.
     *
     * Every route that reaches here requires authentication, so the principal is
     * always a real AccessFlowUserDetails; anonymous requests never get past the
     * filter chain.
     */
    private AccessFlowUserDetails actingUser(Authentication authentication) {
        return (AccessFlowUserDetails) authentication.getPrincipal();
    }
}
