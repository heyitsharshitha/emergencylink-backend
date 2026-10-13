package com.emergencylink.security;

import com.emergencylink.domain.User;
import com.emergencylink.repository.UserRepository;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.List;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private final JwtService jwtService;
    private final UserRepository users;

    public JwtAuthenticationFilter(JwtService jwtService, UserRepository users) {
        this.jwtService = jwtService;
        this.users = users;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        try {
            if (header != null && header.startsWith("Bearer ") && SecurityContextHolder.getContext().getAuthentication() == null) {
                Claims claims = jwtService.parse(header.substring(7));
                Object rawUid = claims.get("uid");
                Long uid = rawUid instanceof Number n ? n.longValue() : null;
                User user = uid == null ? null : users.findByIdWithHospital(uid).orElse(null);
                if (user != null && user.isActive()) {
                    AuthUser principal = new AuthUser(user.getId(), user.getEmail(), user.getFullName(), user.getRole(),
                            user.getHospital() == null ? null : user.getHospital().getId(),
                            user.getHospital() == null ? null : user.getHospital().getName());
                    var auth = new UsernamePasswordAuthenticationToken(principal, null,
                            List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name())));
                    SecurityContextHolder.getContext().setAuthentication(auth);
                    MDC.put("user", user.getEmail());
                }
            }
        } catch (JwtException | IllegalArgumentException ex) {
            log.debug("Rejected invalid JWT: {}", ex.getMessage());
        }
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove("user");
        }
    }
}
