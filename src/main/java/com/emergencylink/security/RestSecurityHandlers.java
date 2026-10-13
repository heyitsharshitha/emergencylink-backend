package com.emergencylink.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.emergencylink.exception.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.time.Instant;

/** JSON 401 / 403 responses for failures that happen inside the security filter chain. */
@Component
public class RestSecurityHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {
    private final ObjectMapper mapper;
    public RestSecurityHandlers(ObjectMapper mapper) { this.mapper = mapper; }

    @Override
    public void commence(HttpServletRequest req, HttpServletResponse res, AuthenticationException ex) throws IOException {
        write(res, req, HttpStatus.UNAUTHORIZED, "Authentication is required or has expired. Please sign in again.");
    }

    @Override
    public void handle(HttpServletRequest req, HttpServletResponse res, AccessDeniedException ex) throws IOException {
        write(res, req, HttpStatus.FORBIDDEN, "Your role is not permitted to access this resource.");
    }

    private void write(HttpServletResponse res, HttpServletRequest req, HttpStatus status, String message) throws IOException {
        res.setStatus(status.value());
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper.writeValue(res.getOutputStream(), new ApiError(Instant.now(), status.value(), status.getReasonPhrase(), message, req.getRequestURI(), null));
    }
}
