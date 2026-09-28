package com.accessflow.exception;

import com.accessflow.dto.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.core.MethodParameter;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;

/**
 * Turns the domain exceptions into the uniform JSON error body.
 *
 * Scoped to {@code com.accessflow.controller} on purpose. An unscoped advice
 * would also match the Thymeleaf controllers added in Phase 3, and its catch-all
 * would turn a failed page render into a JSON body, so the browser UI has its own
 * advice (WebViewAdvice) that renders an HTML error page instead.
 */
@RestControllerAdvice(basePackages = "com.accessflow.controller")
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleUserNotFound(UserNotFoundException ex,
                                                           HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, ex.getMessage(), request, List.of());
    }

    @ExceptionHandler(AccessRequestNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleAccessRequestNotFound(AccessRequestNotFoundException ex,
                                                                     HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, ex.getMessage(), request, List.of());
    }

    @ExceptionHandler(SelfApprovalNotAllowedException.class)
    public ResponseEntity<ErrorResponse> handleSelfApproval(SelfApprovalNotAllowedException ex,
                                                            HttpServletRequest request) {
        return build(HttpStatus.FORBIDDEN, ex.getMessage(), request, List.of());
    }

    @ExceptionHandler(InsufficientRoleException.class)
    public ResponseEntity<ErrorResponse> handleInsufficientRole(InsufficientRoleException ex,
                                                                HttpServletRequest request) {
        return build(HttpStatus.FORBIDDEN, ex.getMessage(), request, List.of());
    }

    @ExceptionHandler(InvalidAccessRequestStateException.class)
    public ResponseEntity<ErrorResponse> handleInvalidState(InvalidAccessRequestStateException ex,
                                                            HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, ex.getMessage(), request, List.of());
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ErrorResponse> handleInvalidCredentials(InvalidCredentialsException ex,
                                                                  HttpServletRequest request) {
        return build(HttpStatus.UNAUTHORIZED, ex.getMessage(), request, List.of());
    }

    @ExceptionHandler(DuplicateUserException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateUser(DuplicateUserException ex,
                                                            HttpServletRequest request) {
        List<ErrorResponse.FieldError> errors = List.of(
                new ErrorResponse.FieldError(ex.getField(), "already in use"));

        return build(HttpStatus.CONFLICT, ex.getMessage(), request, errors);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex,
                                                          HttpServletRequest request) {
        List<ErrorResponse.FieldError> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(this::toFieldError)
                .toList();

        String message = errors.isEmpty() ? "Validation failed" : errors.get(0).message();

        return build(HttpStatus.BAD_REQUEST, message, request, errors);
    }

    /**
     * A constraint declared directly on a handler parameter, e.g.
     * {@code @RequestParam @Min(1) Integer size}.
     *
     * <h2>Why this is a separate handler</h2>
     *
     * A constraint on a {@code @Valid @RequestBody} parameter is reported by
     * {@link MethodArgumentNotValidException} and handled above. A constraint on
     * a path variable, request parameter or header is not: those are validated
     * individually by the argument resolver, and a failure arrives as this
     * exception, which extends ResponseStatusException and would otherwise be
     * answered with Spring's own body.
     *
     * <h2>Why it matters here</h2>
     *
     * {@code GET /api/access-requests/page} validates its paging arguments, so
     * this is what turns {@code ?page=-1} or {@code ?size=1000} into the same
     * ErrorResponse every other client error already gets. Left unmapped, the
     * client would see a shape it has never been given and a status chosen by
     * the framework, and the paginated endpoint's error contract would differ
     * from the rest of the API.
     */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ErrorResponse> handleMethodValidation(HandlerMethodValidationException ex,
                                                                 HttpServletRequest request) {
        // getParameterValidationResults(), not getAllValidationResults(): the latter
        // is deprecated for removal in Spring Framework 6.2. Both are a stream of
        // results carrying a MethodParameter and its errors, so the body built here
        // is unchanged - only the accessor is.
        List<ErrorResponse.FieldError> errors = ex.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> new ErrorResponse.FieldError(
                                parameterName(result.getMethodParameter()), toMessage(error))))
                .toList();

        String message = errors.isEmpty() ? "Validation failed" : errors.get(0).message();

        return build(HttpStatus.BAD_REQUEST, message, request, errors);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableBody(HttpMessageNotReadableException ex,
                                                              HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "Malformed or unreadable request body", request, List.of());
    }

    /**
     * A path variable or request parameter that cannot be converted to the
     * declared type, e.g. /status/BANANA. This is a client mistake, so it is
     * reported as a 400 field error rather than reaching the catch-all 500.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex,
                                                            HttpServletRequest request) {
        String name = ex.getName();
        String rejected = ex.getValue() == null ? "" : String.valueOf(ex.getValue());

        List<ErrorResponse.FieldError> errors = List.of(
                new ErrorResponse.FieldError(name, "has an unsupported value: " + rejected));

        return build(HttpStatus.BAD_REQUEST, "Invalid value for " + name, request, errors);
    }

    /**
     * The duplicate pre-checks in UserService are not atomic, so two concurrent
     * registrations of the same email can both pass them. The unique constraints
     * are the real guard, and this maps the resulting violation to the same 409 a
     * sequential duplicate would get, instead of a misleading 500.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrityViolation(DataIntegrityViolationException ex,
                                                                      HttpServletRequest request) {
        log.warn("Constraint violation on {} {}", request.getMethod(), request.getRequestURI());

        List<ErrorResponse.FieldError> errors = List.of(
                new ErrorResponse.FieldError("user", "employeeId or email is already in use"));

        return build(HttpStatus.CONFLICT, "A user with these details already exists",
                request, errors);
    }

    /**
     * An unmapped path is a 404, not a server fault. Without this the catch-all
     * below would report 500 for every mistyped URL.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResource(NoResourceFoundException ex,
                                                          HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "Resource not found", request, List.of());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), ex);

        return build(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred", request, List.of());
    }

    private ErrorResponse.FieldError toFieldError(FieldError error) {
        String message = error.getDefaultMessage() == null ? "is invalid" : error.getDefaultMessage();
        return new ErrorResponse.FieldError(error.getField(), message);
    }

    /**
     * The name of the rejected parameter, so the response names the argument the
     * caller actually sent rather than an index.
     *
     * Falls back to a placeholder when the name is unavailable. Parameter names
     * come from the bytecode and are present because the Spring Boot parent
     * compiles with -parameters; the fallback keeps the response well formed
     * rather than reporting "null" if that ever stops being true.
     */
    private String parameterName(MethodParameter parameter) {
        return parameter.getParameterName() == null ? "parameter" : parameter.getParameterName();
    }

    private String toMessage(MessageSourceResolvable error) {
        return error.getDefaultMessage() == null ? "is invalid" : error.getDefaultMessage();
    }

    private ResponseEntity<ErrorResponse> build(HttpStatus status, String message,
                                                HttpServletRequest request,
                                                List<ErrorResponse.FieldError> errors) {
        ErrorResponse body = ErrorResponse.of(
                status.value(),
                status.getReasonPhrase(),
                message,
                request.getRequestURI(),
                errors
        );

        return ResponseEntity.status(status).body(body);
    }
}
