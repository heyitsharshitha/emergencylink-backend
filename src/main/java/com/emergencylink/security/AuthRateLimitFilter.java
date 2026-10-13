package com.emergencylink.security;

import com.emergencylink.exception.ApiError;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Limits how often one client address may call the sign-in and registration endpoints, which slows password guessing
 * and automated account creation. It is a fixed-size sliding window held in memory, so it is per application instance;
 * a shared limiter (for example at a gateway) is needed if the backend is scaled out. The client address is the socket
 * address; behind a reverse proxy set {@code server.forward-headers-strategy=native} so it reflects the real client.
 */
@Component
public class AuthRateLimitFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(AuthRateLimitFilter.class);
    private static final long WINDOW_MILLIS = 60_000L;

    private final ObjectMapper mapper;
    private final int maxAttempts;
    private final ConcurrentHashMap<String, Deque<Long>> attempts = new ConcurrentHashMap<>();
    private volatile long lastSweep = System.currentTimeMillis();

    public AuthRateLimitFilter(ObjectMapper mapper, @Value("${app.rate-limit.auth-per-minute:10}") int maxAttempts) {
        this.mapper = mapper;
        this.maxAttempts = maxAttempts;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (maxAttempts <= 0 || !"POST".equalsIgnoreCase(request.getMethod())) return true;
        String path = request.getRequestURI();
        return !("/api/auth/login".equals(path) || "/api/auth/register".equals(path));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long now = System.currentTimeMillis();
        sweep(now);
        String key = request.getRemoteAddr() + "|" + request.getRequestURI();
        boolean allowed;
        long retryAfterSeconds = 1;
        Deque<Long> window = attempts.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (window) {
            while (!window.isEmpty() && now - window.peekFirst() >= WINDOW_MILLIS) window.pollFirst();
            allowed = window.size() < maxAttempts;
            if (allowed) {
                window.addLast(now);
            } else if (!window.isEmpty()) {
                retryAfterSeconds = Math.max(1L, (WINDOW_MILLIS - (now - window.peekFirst()) + 999L) / 1000L);
            }
        }
        if (allowed) {
            chain.doFilter(request, response);
            return;
        }
        log.warn("Rate limit reached for {}", request.getRequestURI());
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader("Retry-After", Long.toString(retryAfterSeconds));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(), new ApiError(Instant.now(), HttpStatus.TOO_MANY_REQUESTS.value(),
                HttpStatus.TOO_MANY_REQUESTS.getReasonPhrase(),
                "Too many attempts. Please wait a minute and try again.", request.getRequestURI(), null));
    }

    /** Drops idle entries so the map cannot grow without bound. */
    private void sweep(long now) {
        if (now - lastSweep < WINDOW_MILLIS) return;
        lastSweep = now;
        attempts.entrySet().removeIf(e -> {
            synchronized (e.getValue()) {
                Long newest = e.getValue().peekLast();
                return newest == null || now - newest >= WINDOW_MILLIS;
            }
        });
    }
}
