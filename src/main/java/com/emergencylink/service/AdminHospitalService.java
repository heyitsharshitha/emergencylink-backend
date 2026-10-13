package com.emergencylink.service;

import com.emergencylink.domain.Hospital;
import com.emergencylink.dto.AdminDtos.HospitalCreateRequest;
import com.emergencylink.dto.AdminDtos.HospitalEditRequest;
import com.emergencylink.dto.HospitalDtos.HospitalDto;
import com.emergencylink.dto.HospitalDtos.HospitalUpdateRequest;
import com.emergencylink.exception.ApiException;
import com.emergencylink.repository.HospitalRepository;
import com.emergencylink.security.AuthUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.stream.Collectors;

/** Administrator-only hospital record creation and editing, with a before/after audit trail. */
@Service
public class AdminHospitalService {
    private final HospitalRepository hospitals;
    private final HospitalService hospitalService;
    private final CatalogService catalog;
    private final AuditService audit;

    public AdminHospitalService(HospitalRepository hospitals, HospitalService hospitalService, CatalogService catalog, AuditService audit) {
        this.hospitals = hospitals;
        this.hospitalService = hospitalService;
        this.catalog = catalog;
        this.audit = audit;
    }

    /** Creates a hospital record. It cannot receive referrals until a receiving-hospital user account is linked to it. */
    @Transactional
    public HospitalDto create(HospitalCreateRequest req, AuthUser admin) {
        HospitalUpdateRequest r = req.hospital();
        String name = r.name().trim();
        if (hospitals.existsByNameIgnoreCase(name)) throw ApiException.conflict("A hospital named \"" + name + "\" already exists.");
        Hospital h = new Hospital();
        h.setActive(true);
        apply(h, r, name);
        h = hospitals.save(h);
        audit.record(admin, "HOSPITAL_CREATED", "Hospital", h.getId(), h.getName(), "Hospital record created by an administrator",
                req.reason() == null || req.reason().isBlank() ? null : req.reason().trim(), null, snapshot(h));
        return hospitalService.toDtos(List.of(h)).get(0);
    }

    @Transactional
    public HospitalDto edit(Long id, HospitalEditRequest req, AuthUser admin) {
        Hospital h = hospitals.findDetailById(id).orElseThrow(() -> ApiException.notFound("Hospital"));
        HospitalUpdateRequest r = req.hospital();
        String name = r.name().trim();
        if (!h.getName().equalsIgnoreCase(name) && hospitals.existsByNameIgnoreCase(name)) {
            throw ApiException.conflict("A hospital named \"" + name + "\" already exists.");
        }
        String before = snapshot(h);
        apply(h, r, name);
        String after = snapshot(h);
        if (before.equals(after)) throw ApiException.badRequest("No changes were made. Change at least one field.");
        audit.record(admin, "HOSPITAL_ADMIN_EDITED", "Hospital", h.getId(), h.getName(), "Hospital record edited by an administrator",
                req.reason().trim(), before, after);
        return hospitalService.toDtos(List.of(h)).get(0);
    }

    private void apply(Hospital h, HospitalUpdateRequest r, String name) {
        h.setName(name);
        h.setType(r.type());
        h.setAddress(r.address());
        h.setCity(r.city().trim());
        h.setState(r.state().trim());
        h.setPhone(r.phone());
        h.setEmail(r.email());
        h.setEmergencyContact(r.emergencyContact());
        h.setLatitude(r.latitude());
        h.setLongitude(r.longitude());
        if (r.emergencyServices() != null) h.setEmergencyServices(r.emergencyServices());
        if (r.specialties() != null) {
            h.getSpecialties().clear();
            h.getSpecialties().addAll(catalog.resolveSpecialties(r.specialties()));
        }
        if (r.facilities() != null) {
            h.getFacilities().clear();
            h.getFacilities().addAll(catalog.resolveFacilities(r.facilities()));
        }
    }

    /** One-line summary of the audited fields, stable between calls so before and after can be compared. */
    private static String snapshot(Hospital h) {
        String sp = h.getSpecialties().stream().map(s -> s.getName()).sorted().collect(Collectors.joining(", "));
        String fa = h.getFacilities().stream().map(f -> f.getName()).sorted().collect(Collectors.joining(", "));
        return "name=" + h.getName() + "; type=" + h.getType() + "; city=" + h.getCity() + "; state=" + h.getState()
                + "; address=" + nz(h.getAddress()) + "; phone=" + nz(h.getPhone()) + "; email=" + nz(h.getEmail())
                + "; emergencyContact=" + nz(h.getEmergencyContact()) + "; lat=" + h.getLatitude() + "; lng=" + h.getLongitude()
                + "; emergencyServices=" + h.isEmergencyServices() + "; specialties=[" + sp + "]; facilities=[" + fa + "]";
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
