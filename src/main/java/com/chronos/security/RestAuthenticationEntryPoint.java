package com.chronos.security;

import com.chronos.common.ApiError;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Produces the 401 body for unauthenticated requests.
 *
 * <p><b>Why this exists next to {@code GlobalExceptionHandler}:</b> an
 * {@code @RestControllerAdvice} only sees exceptions raised inside the DispatcherServlet.
 * Authentication fails earlier, in the filter chain, so Spring Security calls an
 * {@code AuthenticationEntryPoint} instead. The default one sends a
 * {@code WWW-Authenticate: Basic} challenge, which makes browsers pop a native login dialog —
 * useless for a JSON API. This returns the same {@link ApiError} shape as everything else.
 */
@Component
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    public RestAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        reject(request, response, "Authentication required");
    }

    /** Also called directly by {@link JwtAuthenticationFilter} for expired/invalid tokens. */
    void reject(HttpServletRequest request, HttpServletResponse response, String message)
            throws IOException {

        // If something already started writing the response there is nothing safe to do.
        if (response.isCommitted()) {
            return;
        }

        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");

        objectMapper.writeValue(response.getOutputStream(), ApiError.of(
                HttpStatus.UNAUTHORIZED.value(),
                HttpStatus.UNAUTHORIZED.getReasonPhrase(),
                message,
                request.getRequestURI()));
    }
}
