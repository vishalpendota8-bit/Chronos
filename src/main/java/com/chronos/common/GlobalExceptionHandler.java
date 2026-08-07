package com.chronos.common;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.List;

/**
 * One place that turns exceptions into {@link ApiError} bodies.
 *
 * <p>Note this arrives a module early: the plan puts the advice in M3, but /auth/register and
 * /auth/login already need "409 duplicate email" and "401 bad credentials" to be well-formed
 * JSON rather than Boot's default whitelabel body, so it lands here.
 *
 * <p><b>Important scope limit:</b> an advice only sees exceptions thrown *inside* the
 * DispatcherServlet. Failures in the security filter chain (missing/expired token) happen
 * before dispatch and are handled by
 * {@link com.chronos.security.RestAuthenticationEntryPoint} instead — which is why that class
 * exists and duplicates a little of this formatting.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Bean Validation failures: report every bad field at once so a form can highlight them all. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex,
                                                     HttpServletRequest request) {
        List<ApiError.FieldError> fields = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new ApiError.FieldError(fe.getField(), fe.getDefaultMessage()))
                .toList();

        return ResponseEntity.badRequest().body(ApiError.of(
                HttpStatus.BAD_REQUEST.value(),
                "Validation failed",
                "One or more fields are invalid",
                request.getRequestURI(),
                fields));
    }

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<ApiError> handleBadRequest(BadRequestException ex,
                                                     HttpServletRequest request) {
        return status(HttpStatus.BAD_REQUEST, ex.getMessage(), request);
    }

    /** e.g. /users/abc where a Long is expected — a client bug, not a server one. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException ex,
                                                       HttpServletRequest request) {
        return status(HttpStatus.BAD_REQUEST,
                "Parameter '" + ex.getName() + "' has an invalid value", request);
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(NotFoundException ex,
                                                   HttpServletRequest request) {
        return status(HttpStatus.NOT_FOUND, ex.getMessage(), request);
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ApiError> handleConflict(ConflictException ex,
                                                   HttpServletRequest request) {
        return status(HttpStatus.CONFLICT, ex.getMessage(), request);
    }

    /**
     * Login failures. The message is deliberately vague and identical for "no such user" and
     * "wrong password" — distinguishing them would let an attacker enumerate valid emails.
     */
    @ExceptionHandler({BadCredentialsException.class, AuthenticationException.class})
    public ResponseEntity<ApiError> handleAuthentication(AuthenticationException ex,
                                                         HttpServletRequest request) {
        log.debug("Authentication failed for {}: {}", request.getRequestURI(), ex.getMessage());
        return status(HttpStatus.UNAUTHORIZED, "Invalid email or password", request);
    }

    /** Thrown by @PreAuthorize on a method the caller is authenticated for but not allowed to call. */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException ex,
                                                       HttpServletRequest request) {
        return status(HttpStatus.FORBIDDEN, "You do not have permission to perform this action", request);
    }

    /**
     * Last resort. The real cause is logged with a stack trace but never returned: internal
     * messages leak class names, SQL, and sometimes data.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), ex);
        return status(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected server error", request);
    }

    private ResponseEntity<ApiError> status(HttpStatus httpStatus, String message,
                                            HttpServletRequest request) {
        return ResponseEntity.status(httpStatus).body(ApiError.of(
                httpStatus.value(),
                httpStatus.getReasonPhrase(),
                message,
                request.getRequestURI()));
    }
}
