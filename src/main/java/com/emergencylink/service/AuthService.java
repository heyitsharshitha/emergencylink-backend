package com.emergencylink.service;

import com.emergencylink.domain.Hospital;
import com.emergencylink.domain.Role;
import com.emergencylink.domain.User;
import com.emergencylink.dto.AuthDtos.*;
import com.emergencylink.exception.ApiException;
import com.emergencylink.repository.HospitalRepository;
import com.emergencylink.repository.UserRepository;
import com.emergencylink.security.AuthUser;
import com.emergencylink.security.JwtService;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;

@Service
public class AuthService {
    private final UserRepository users;
    private final HospitalRepository hospitals;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final CatalogService catalog;
    private final AuditService audit;

    public AuthService(UserRepository users, HospitalRepository hospitals, PasswordEncoder encoder, JwtService jwt,
                       CatalogService catalog, AuditService audit) {
        this.users = users;
        this.hospitals = hospitals;
        this.encoder = encoder;
        this.jwt = jwt;
        this.catalog = catalog;
        this.audit = audit;
    }

    @Transactional
    public AuthResponse login(LoginRequest req) {
        User user = users.findByEmailWithHospital(req.email().trim()).orElse(null);
        // Same message for unknown e-mail and wrong password so accounts cannot be enumerated.
        if (user == null || !encoder.matches(req.password(), user.getPasswordHash())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid e-mail or password.");
        }
        if (!user.isActive()) throw new ApiException(HttpStatus.FORBIDDEN, "This account has been deactivated. Contact an administrator.");
        audit.record(toPrincipal(user), "LOGIN", "User", user.getId(), user.getEmail(), "Signed in");
        return respond(user);
    }

    @Transactional
    public AuthResponse register(RegisterRequest req) {
        if (req.role() == Role.ADMIN) throw ApiException.badRequest("Administrator accounts cannot be self-registered.");
        if (users.existsByEmailIgnoreCase(req.email().trim())) throw ApiException.conflict("An account with this e-mail already exists.");
        if (hospitals.existsByNameIgnoreCase(req.hospitalName().trim())) throw ApiException.conflict("A hospital with this name is already registered.");

        Hospital h = new Hospital();
        h.setName(req.hospitalName().trim());
        h.setType(req.hospitalType());
        h.setAddress(req.address());
        h.setCity(req.city().trim());
        h.setState(req.state().trim());
        h.setPhone(req.phone());
        h.setEmail(req.email().trim());
        h.setEmergencyContact(req.emergencyContact());
        h.setLatitude(req.latitude());
        h.setLongitude(req.longitude());
        h.getSpecialties().addAll(catalog.resolveSpecialties(req.specialties()));
        h.getFacilities().addAll(catalog.resolveFacilities(req.facilities()));
        h = hospitals.save(h);

        User u = new User();
        u.setEmail(req.email().trim().toLowerCase());
        u.setFullName(req.fullName().trim());
        u.setPasswordHash(encoder.encode(req.password()));
        u.setRole(req.role());
        u.setHospital(h);
        u = users.save(u);
        audit.record(toPrincipal(u), "HOSPITAL_REGISTERED", "Hospital", h.getId(), h.getName(), "New hospital account registered as " + req.role());
        return respond(u);
    }

    @Transactional(readOnly = true)
    public UserDto me(AuthUser principal) {
        User u = users.findByIdWithHospital(principal.id()).orElseThrow(() -> ApiException.notFound("User"));
        return toDto(u);
    }

    @Transactional
    public void logout(AuthUser principal) {
        // JWTs are stateless; the client discards the token. We keep an audit trail of the sign-out.
        audit.record(principal, "LOGOUT", "User", principal.id(), principal.email(), "Signed out");
    }

    private AuthResponse respond(User user) {
        Instant exp = jwt.expiryFromNow();
        return new AuthResponse(jwt.generate(user, exp), "Bearer", exp, toDto(user));
    }

    public static UserDto toDto(User u) {
        return new UserDto(u.getId(), u.getEmail(), u.getFullName(), u.getRole(),
                u.getHospital() == null ? null : u.getHospital().getId(),
                u.getHospital() == null ? null : u.getHospital().getName(), u.isActive(), u.getCreatedAt());
    }

    private static AuthUser toPrincipal(User u) {
        return new AuthUser(u.getId(), u.getEmail(), u.getFullName(), u.getRole(),
                u.getHospital() == null ? null : u.getHospital().getId(),
                u.getHospital() == null ? null : u.getHospital().getName());
    }
}
