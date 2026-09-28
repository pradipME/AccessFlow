package com.accessflow.web;

import com.accessflow.exception.AccessRequestNotFoundException;
import com.accessflow.exception.InsufficientRoleException;
import com.accessflow.exception.InvalidAccessRequestStateException;
import com.accessflow.exception.SelfApprovalNotAllowedException;
import com.accessflow.exception.UserNotFoundException;
import com.accessflow.security.AccessFlowUserDetails;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.validation.BindException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.ModelAndView;

/**
 * Presentation concerns for the Thymeleaf controllers: who is signed in, and how
 * a failure inside one of them becomes a page.
 *
 * Scoped to {@code com.accessflow.web} so it never competes with
 * GlobalExceptionHandler, which is scoped to the REST controllers and answers
 * with JSON. A request is therefore answered by exactly one of the two, and the
 * two cannot both claim it.
 *
 * Failures that never reach a controller method are not this advice's business:
 * a URL that matches nothing is {@link WebErrorPageResolver}, and a refusal in
 * the filter chain is the SecurityConfig's own handler.
 */
@ControllerAdvice(basePackages = "com.accessflow.web")
public class WebViewAdvice {

    private static final Logger log = LoggerFactory.getLogger(WebViewAdvice.class);

    private final WebErrorPage errorPage;

    public WebViewAdvice(WebErrorPage errorPage) {
        this.errorPage = errorPage;
    }

    /**
     * The signed-in employee, offered to every template.
     *
     * Null for an anonymous request, which is how the login page renders without
     * a navigation bar; the layout guards on it. Never read from a request
     * parameter: a page that says who you are has to get that from the
     * authenticated principal.
     */
    @ModelAttribute("currentUser")
    public CurrentUser currentUser(Authentication authentication) {
        if (authentication == null
                || !(authentication.getPrincipal() instanceof AccessFlowUserDetails details)) {
            return null;
        }
        return CurrentUser.from(details);
    }

    @ExceptionHandler(UserNotFoundException.class)
    public ModelAndView handleUserNotFound(UserNotFoundException ex,
                                           HttpServletRequest request,
                                           HttpServletResponse response) {
        return errorPage(HttpStatus.NOT_FOUND, ex.getMessage(), request, response);
    }

    @ExceptionHandler(AccessRequestNotFoundException.class)
    public ModelAndView handleAccessRequestNotFound(AccessRequestNotFoundException ex,
                                                   HttpServletRequest request,
                                                   HttpServletResponse response) {
        return errorPage(HttpStatus.NOT_FOUND, ex.getMessage(), request, response);
    }

    @ExceptionHandler(InsufficientRoleException.class)
    public ModelAndView handleInsufficientRole(InsufficientRoleException ex,
                                               HttpServletRequest request,
                                               HttpServletResponse response) {
        return errorPage(HttpStatus.FORBIDDEN, ex.getMessage(), request, response);
    }

    @ExceptionHandler(SelfApprovalNotAllowedException.class)
    public ModelAndView handleSelfApproval(SelfApprovalNotAllowedException ex,
                                           HttpServletRequest request,
                                           HttpServletResponse response) {
        return errorPage(HttpStatus.FORBIDDEN, ex.getMessage(), request, response);
    }

    @ExceptionHandler(InvalidAccessRequestStateException.class)
    public ModelAndView handleInvalidState(InvalidAccessRequestStateException ex,
                                           HttpServletRequest request,
                                           HttpServletResponse response) {
        return errorPage(HttpStatus.CONFLICT, ex.getMessage(), request, response);
    }

    /**
     * A form whose values cannot be bound, or a path variable or query parameter
     * with an unusable value such as ?status=BANANA. A client mistake, so a 400
     * page rather than a server fault.
     */
    @ExceptionHandler({BindException.class, MethodArgumentTypeMismatchException.class})
    public ModelAndView handleUnusableInput(Exception ex,
                                            HttpServletRequest request,
                                            HttpServletResponse response) {
        return errorPage(HttpStatus.BAD_REQUEST, "The submitted values could not be processed", request, response);
    }

    /**
     * A constraint declared directly on a handler parameter rather than on a form
     * object, which {@link BindException} does not cover.
     *
     * The reviewer queue's page size is validated this way, so this is what keeps
     * {@code ?size=1000} a 400 page in the browser instead of the framework's
     * default handling - the same reason GlobalExceptionHandler maps it for the
     * API. Listed separately from the handler above because it is a different
     * exception type, and grouping it in would mean rewriting that message.
     */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ModelAndView handleMethodValidation(HandlerMethodValidationException ex,
                                               HttpServletRequest request,
                                               HttpServletResponse response) {
        return errorPage(HttpStatus.BAD_REQUEST, "The requested page cannot be displayed", request, response);
    }

    /**
     * Mirrors the catch-all in GlobalExceptionHandler, and gives the same
     * guarantee: the page states that something went wrong and nothing else. The
     * detail goes to the log, where it belongs; an exception message can quote
     * internal state, and this HTML is shown to a browser.
     */
    @ExceptionHandler(Exception.class)
    public ModelAndView handleUnexpected(Exception ex, HttpServletRequest request,
                                         HttpServletResponse response) {
        log.error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), ex);

        return errorPage(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred", request, response);
    }

    /**
     * Builds the error page and sets the status on the response.
     *
     * The status is written explicitly because the status of a ModelAndView
     * returned from an exception handler is not applied on its own.
     *
     * A URL that matches no controller never reaches this advice, because there
     * is no controller method to match it against; {@link WebErrorPageResolver}
     * answers those.
     */
    private ModelAndView errorPage(HttpStatus status, String message, HttpServletRequest request,
                                   HttpServletResponse response) {
        return errorPage.forStatus(status, message, request, response);
    }
}
