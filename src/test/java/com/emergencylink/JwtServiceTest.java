package com.emergencylink;

import com.emergencylink.config.AppProperties;
import com.emergencylink.domain.Role;
import com.emergencylink.domain.User;
import com.emergencylink.security.JwtService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class JwtServiceTest {
    private JwtService service(String secret) {
        return new JwtService(new AppProperties(new AppProperties.Jwt(secret, 60), new AppProperties.Cors(List.of()),
                new AppProperties.Resources(120), new AppProperties.Seed(false), new AppProperties.Llm("", "", "", 100, 6)));
    }

    private User user() {
        User u = new User(); u.setId(7L); u.setEmail("a@b.demo"); u.setRole(Role.ADMIN); return u;
    }

    @Test void roundTripCarriesIdAndRole() {
        JwtService s = service("s".repeat(40));
        Claims c = s.parse(s.generate(user(), Instant.now().plus(1, ChronoUnit.HOURS)));
        assertEquals("a@b.demo", c.getSubject());
        assertEquals(7L, ((Number) c.get("uid")).longValue());
        assertEquals("ADMIN", c.get("role", String.class));
    }

    @Test void tokenSignedWithAnotherSecretIsRejected() {
        String token = service("a".repeat(40)).generate(user(), Instant.now().plus(1, ChronoUnit.HOURS));
        assertThrows(JwtException.class, () -> service("b".repeat(40)).parse(token));
    }

    @Test void expiredTokenIsRejected() {
        JwtService s = service("s".repeat(40));
        assertThrows(JwtException.class, () -> s.parse(s.generate(user(), Instant.now().minus(1, ChronoUnit.HOURS))));
    }

    @Test void shortSecretFailsFast() { assertThrows(IllegalStateException.class, () -> service("short")); }
}
