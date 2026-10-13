package com.emergencylink.service;

import com.emergencylink.domain.*;
import com.emergencylink.dto.CommonDtos.PageResponse;
import com.emergencylink.dto.HospitalDtos.HospitalDto;
import com.emergencylink.dto.HospitalDtos.HospitalUpdateRequest;
import com.emergencylink.exception.ApiException;
import com.emergencylink.repository.HospitalRepository;
import com.emergencylink.repository.HospitalResourceRepository;
import com.emergencylink.repository.ReferralRepository;
import com.emergencylink.security.AuthUser;
import jakarta.persistence.criteria.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class HospitalService {
    private static final List<ReferralStatus> INACTIVE = List.of(ReferralStatus.COMPLETED, ReferralStatus.CANCELLED, ReferralStatus.DECLINED);

    private final HospitalRepository hospitals;
    private final HospitalResourceRepository resources;
    private final ReferralRepository referrals;
    private final HospitalMapper mapper;
    private final CatalogService catalog;
    private final AuditService audit;
    private final com.emergencylink.repository.UserRepository users;

    public HospitalService(HospitalRepository hospitals, HospitalResourceRepository resources, ReferralRepository referrals,
                           HospitalMapper mapper, CatalogService catalog, AuditService audit,
                           com.emergencylink.repository.UserRepository users) {
        this.users = users;
        this.hospitals = hospitals;
        this.resources = resources;
        this.referrals = referrals;
        this.mapper = mapper;
        this.catalog = catalog;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public PageResponse<HospitalDto> search(String q, String city, String specialty, String facility, boolean availableOnly,
                                            Boolean active, AuthUser user, int page, int size) {
        Specification<Hospital> spec = (root, query, cb) -> {
            query.distinct(true);
            List<Predicate> p = new ArrayList<>();
            if (!user.isAdmin()) p.add(cb.isTrue(root.get("active")));
            else if (active != null) p.add(cb.equal(root.get("active"), active));
            if (q != null && !q.isBlank()) {
                String like = "%" + q.trim().toLowerCase() + "%";
                p.add(cb.or(cb.like(cb.lower(root.get("name")), like), cb.like(cb.lower(root.get("city")), like),
                        cb.like(cb.lower(root.get("address")), like)));
            }
            if (city != null && !city.isBlank()) p.add(cb.like(cb.lower(root.get("city")), "%" + city.trim().toLowerCase() + "%"));
            if (specialty != null && !specialty.isBlank()) {
                Join<Hospital, Specialty> j = root.join("specialties", JoinType.INNER);
                p.add(cb.equal(cb.lower(j.get("name")), specialty.trim().toLowerCase()));
            }
            if (facility != null && !facility.isBlank()) {
                Join<Hospital, Facility> j = root.join("facilities", JoinType.INNER);
                p.add(cb.equal(cb.lower(j.get("name")), facility.trim().toLowerCase()));
            }
            if (availableOnly) {
                Subquery<Long> sub = query.subquery(Long.class);
                Root<HospitalResource> r = sub.from(HospitalResource.class);
                sub.select(r.get("id")).where(
                        cb.equal(r.get("hospital"), root),
                        r.get("type").in(ResourceType.EMERGENCY_BED, ResourceType.GENERAL_BED, ResourceType.ICU_BED),
                        cb.greaterThan(r.<Integer>get("available"), 0));
                p.add(cb.exists(sub));
            }
            return cb.and(p.toArray(new Predicate[0]));
        };
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100), Sort.by("name").ascending());
        Page<Hospital> result = hospitals.findAll(spec, pageable);
        return PageResponse.of(new PageImpl<>(toDtos(result.getContent()), pageable, result.getTotalElements()));
    }

    @Transactional(readOnly = true)
    public HospitalDto get(Long id, AuthUser user) {
        Hospital h = hospitals.findDetailById(id).orElseThrow(() -> ApiException.notFound("Hospital"));
        if (!h.isActive() && !user.isAdmin() && !id.equals(user.hospitalId())) throw ApiException.notFound("Hospital");
        return toDtos(List.of(h)).get(0);
    }

    @Transactional(readOnly = true)
    public HospitalDto mine(AuthUser user) {
        if (user.hospitalId() == null) throw ApiException.badRequest("Your account is not linked to a hospital.");
        return get(user.hospitalId(), user);
    }

    @Transactional
    public HospitalDto update(Long id, HospitalUpdateRequest req, AuthUser user) {
        if (!user.isAdmin() && !id.equals(user.hospitalId())) {
            throw ApiException.forbidden("You can only edit your own hospital profile.");
        }
        Hospital h = hospitals.findDetailById(id).orElseThrow(() -> ApiException.notFound("Hospital"));
        String name = req.name().trim();
        if (!h.getName().equalsIgnoreCase(name) && hospitals.existsByNameIgnoreCase(name)) {
            throw ApiException.conflict("A hospital named \"" + name + "\" already exists.");
        }
        h.setName(name);
        h.setType(req.type());
        h.setAddress(req.address());
        h.setCity(req.city().trim());
        h.setState(req.state().trim());
        h.setPhone(req.phone());
        h.setEmail(req.email());
        h.setEmergencyContact(req.emergencyContact());
        h.setLatitude(req.latitude());
        h.setLongitude(req.longitude());
        if (req.emergencyServices() != null) h.setEmergencyServices(req.emergencyServices());
        if (req.specialties() != null) {
            h.getSpecialties().clear();
            h.getSpecialties().addAll(catalog.resolveSpecialties(req.specialties()));
        }
        if (req.facilities() != null) {
            h.getFacilities().clear();
            h.getFacilities().addAll(catalog.resolveFacilities(req.facilities()));
        }
        audit.record(user, "HOSPITAL_UPDATED", "Hospital", h.getId(), h.getName(), "Hospital profile updated");
        return toDtos(List.of(h)).get(0);
    }

    @Transactional
    public HospitalDto setActive(Long id, boolean active, String reason, AuthUser admin) {
        if (!active && (reason == null || reason.isBlank())) throw ApiException.badRequest("A reason is required to deactivate a hospital.");
        Hospital h = hospitals.findDetailById(id).orElseThrow(() -> ApiException.notFound("Hospital"));
        h.setActive(active);
        audit.record(admin, active ? "HOSPITAL_ACTIVATED" : "HOSPITAL_DEACTIVATED", "Hospital", h.getId(), h.getName(), reason == null || reason.isBlank() ? null : reason.trim());
        return toDtos(List.of(h)).get(0);
    }

    /** Batch-maps hospitals with their resources and active-referral counts (two queries total). */
    @Transactional(readOnly = true)
    public List<HospitalDto> toDtos(List<Hospital> list) {
        if (list.isEmpty()) return List.of();
        List<Long> ids = list.stream().map(Hospital::getId).toList();
        Map<Long, List<HospitalResource>> byHospital = resources.findByHospitalIds(ids).stream()
                .collect(Collectors.groupingBy(r -> r.getHospital().getId()));
        Map<Long, Long> active = new HashMap<>();
        for (Object[] row : referrals.countActiveByTarget(INACTIVE)) active.put((Long) row[0], (Long) row[1]);
        Set<Long> receiving = new HashSet<>(users.findReceivingHospitalIds());
        return list.stream().map(h -> mapper.toDto(h, byHospital.getOrDefault(h.getId(), List.of()), active.getOrDefault(h.getId(), 0L), receiving.contains(h.getId()))).toList();
    }

    @Transactional(readOnly = true)
    public List<HospitalDto> network() {
        return toDtos(hospitals.findByActiveTrue().stream().sorted(Comparator.comparing(Hospital::getName)).toList());
    }
}
