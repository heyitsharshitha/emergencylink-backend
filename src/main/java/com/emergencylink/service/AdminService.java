package com.emergencylink.service;

import com.emergencylink.domain.*;
import com.emergencylink.dto.AuthDtos.UserDto;
import com.emergencylink.dto.CommonDtos.PageResponse;
import com.emergencylink.dto.DashboardDtos.AuditEventDto;
import com.emergencylink.dto.DashboardDtos.SystemStatusDto;
import com.emergencylink.exception.ApiException;
import com.emergencylink.repository.*;
import com.emergencylink.security.AuthUser;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

@Service
public class AdminService {
    private final UserRepository users;
    private final AuditEventRepository auditRepo;
    private final ReferralRepository referrals;
    private final HospitalRepository hospitals;
    private final HospitalResourceRepository resources;
    private final AuditService audit;
    private final HospitalMapper mapper;
    private final com.emergencylink.config.AppProperties props;
    private final SettingsService settings;

    public AdminService(UserRepository users, AuditEventRepository auditRepo, ReferralRepository referrals, HospitalRepository hospitals,
                        HospitalResourceRepository resources, AuditService audit, HospitalMapper mapper, com.emergencylink.config.AppProperties props,
                        SettingsService settings) {
        this.settings = settings;
        this.users = users;
        this.auditRepo = auditRepo;
        this.referrals = referrals;
        this.hospitals = hospitals;
        this.resources = resources;
        this.audit = audit;
        this.mapper = mapper;
        this.props = props;
    }

    @Transactional(readOnly = true)
    public PageResponse<UserDto> users(String q, Role role, int page, int size) {
        String query = q == null || q.isBlank() ? null : q.trim();
        return PageResponse.of(users.search(query, role, PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100), Sort.by("id")))
                .map(com.emergencylink.service.AuthService::toDto));
    }

    @Transactional
    public UserDto setUserActive(Long id, boolean active, String reason, AuthUser admin) {
        if (id.equals(admin.id())) throw ApiException.badRequest("You cannot change the status of your own account.");
        if (!active && (reason == null || reason.isBlank())) throw ApiException.badRequest("A reason is required to deactivate an account.");
        User u = users.findByIdWithHospital(id).orElseThrow(() -> ApiException.notFound("User"));
        if (u.getRole() == Role.ADMIN && !active && u.isActive()
                && users.findAll().stream().filter(x -> x.getRole() == Role.ADMIN && x.isActive() && !x.getId().equals(u.getId())).count() == 0) {
            throw ApiException.conflict("This is the last active administrator and cannot be deactivated.");
        }
        u.setActive(active);
        audit.record(admin, active ? "USER_ACTIVATED" : "USER_DEACTIVATED", "User", u.getId(), u.getEmail(), reason == null || reason.isBlank() ? null : reason.trim());
        return AuthService.toDto(u);
    }

    @Transactional(readOnly = true)
    public PageResponse<AuditEventDto> audit(String q, String action, String actor, String entityType, String outcome, LocalDate from, LocalDate to, int page, int size) {
        Specification<AuditEvent> spec = (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            if (q != null && !q.isBlank()) {
                String like = "%" + q.trim().toLowerCase() + "%";
                p.add(cb.or(cb.like(cb.lower(root.get("actorEmail")), like), cb.like(cb.lower(root.get("entityLabel")), like),
                        cb.like(cb.lower(root.get("detail")), like), cb.like(cb.lower(root.get("action")), like)));
            }
            if (action != null && !action.isBlank()) p.add(cb.equal(root.get("action"), action.trim()));
            if (actor != null && !actor.isBlank()) p.add(cb.like(cb.lower(root.get("actorEmail")), "%" + actor.trim().toLowerCase() + "%"));
            if (entityType != null && !entityType.isBlank()) p.add(cb.equal(root.get("entityType"), entityType.trim()));
            if (outcome != null && !outcome.isBlank()) {
                if ("SUCCESS".equalsIgnoreCase(outcome.trim())) p.add(cb.or(cb.isNull(root.get("outcome")), cb.equal(root.get("outcome"), "SUCCESS")));
                else p.add(cb.equal(root.get("outcome"), outcome.trim().toUpperCase()));
            }
            if (from != null) p.add(cb.greaterThanOrEqualTo(root.<Instant>get("createdAt"), from.atStartOfDay(ZoneOffset.UTC).toInstant()));
            if (to != null) p.add(cb.lessThan(root.<Instant>get("createdAt"), to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()));
            return cb.and(p.toArray(new Predicate[0]));
        };
        return PageResponse.of(auditRepo.findAll(spec, PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100), Sort.by(Sort.Direction.DESC, "createdAt", "id")))
                .map(e -> new AuditEventDto(e.getId(), e.getActorEmail(), e.getActorRole(), e.getAction(), e.getEntityType(), e.getEntityId(),
                        e.getEntityLabel(), e.getDetail(), e.getReason(), e.getOutcome() == null ? "SUCCESS" : e.getOutcome(),
                        e.getBeforeValue(), e.getAfterValue(), e.getCreatedAt())));
    }

    /**
     * CSV of referral analytics. Deliberately excludes patient identifiers and clinical summaries,
     * so an export can never leak the (demo) patient data.
     */
    @Transactional(readOnly = true)
    public String referralsCsv(ReferralStatus status, Urgency urgency, LocalDate from, LocalDate to, AuthUser admin) {
        Specification<Referral> spec = (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            if (status != null) p.add(cb.equal(root.get("status"), status));
            if (urgency != null) p.add(cb.equal(root.get("urgency"), urgency));
            if (from != null) p.add(cb.greaterThanOrEqualTo(root.<Instant>get("createdAt"), from.atStartOfDay(ZoneOffset.UTC).toInstant()));
            if (to != null) p.add(cb.lessThan(root.<Instant>get("createdAt"), to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()));
            return cb.and(p.toArray(new Predicate[0]));
        };
        List<Referral> list = referrals.findAll(spec, Sort.by(Sort.Direction.DESC, "createdAt"));
        StringBuilder sb = new StringBuilder();
        sb.append("# EmergencyLink referral analytics export\n");
        sb.append("# Generated at: ").append(Instant.now()).append(" (UTC)\n");
        sb.append("# Demonstration data: fictional hospitals and referrals, not real patient or hospital information. Patient identifiers and clinical details are excluded.\n");
        sb.append("referral_code,urgency,status,specialty,referring_hospital,receiving_hospital,icu_required,created_at,updated_at,decided_at\n");
        for (Referral r : list) {
            sb.append(csv(r.getReferralCode())).append(',').append(r.getUrgency()).append(',').append(r.getStatus()).append(',')
              .append(csv(r.getRequiredSpecialty() == null ? "" : r.getRequiredSpecialty().getName())).append(',')
              .append(csv(r.getReferringHospital().getName())).append(',')
              .append(csv(r.getTargetHospital() == null ? "" : r.getTargetHospital().getName())).append(',')
              .append(r.isIcuRequired()).append(',').append(r.getCreatedAt()).append(',').append(r.getUpdatedAt()).append(',')
              .append(r.getDecidedAt() == null ? "" : r.getDecidedAt()).append('\n');
        }
        audit.record(admin, "REPORT_EXPORTED", "Report", null, "referrals.csv", list.size() + " rows");
        return sb.toString();
    }

    private static String csv(String s) {
        if (s == null) return "";
        String v = s.replace("\"", "\"\"");
        // Neutralise spreadsheet formula injection.
        if (!v.isEmpty() && "=+-@".indexOf(v.charAt(0)) >= 0) v = "'" + v;
        return "\"" + v + "\"";
    }

    @Transactional(readOnly = true)
    public SystemStatusDto status() {
        Instant last = resources.findAll().stream().map(HospitalResource::getUpdatedAt).max(Instant::compareTo).orElse(null);
        long stale = resources.findAll().stream().filter(r -> mapper.isStale(r.getUpdatedAt())).count();
        String mode = props.llm().apiKey() == null || props.llm().apiKey().isBlank() ? "DEMO" : "LIVE";
        return new SystemStatusDto("UP", "UP", true, "1.0.0", mode, Instant.now(), last, hospitals.count(), referrals.count(), stale,
                settings.staleMinutes());
    }
}
