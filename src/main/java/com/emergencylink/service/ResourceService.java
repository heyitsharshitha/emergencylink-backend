package com.emergencylink.service;

import com.emergencylink.domain.Hospital;
import com.emergencylink.domain.HospitalResource;
import com.emergencylink.domain.ResourceType;
import com.emergencylink.dto.HospitalDtos.ResourceBatchRequest;
import com.emergencylink.dto.HospitalDtos.ResourceDto;
import com.emergencylink.dto.HospitalDtos.ResourceUpdateRequest;
import com.emergencylink.exception.ApiException;
import com.emergencylink.repository.HospitalRepository;
import com.emergencylink.repository.HospitalResourceRepository;
import com.emergencylink.security.AuthUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;

@Service
public class ResourceService {
    private final HospitalResourceRepository resources;
    private final HospitalRepository hospitals;
    private final HospitalMapper mapper;
    private final AuditService audit;
    private final com.emergencylink.repository.ResourceChangeRepository resourceChanges;

    public ResourceService(HospitalResourceRepository resources, HospitalRepository hospitals, HospitalMapper mapper, AuditService audit,
                           com.emergencylink.repository.ResourceChangeRepository changes) {
        this.resourceChanges = changes;
        this.resources = resources;
        this.hospitals = hospitals;
        this.mapper = mapper;
        this.audit = audit;
    }

    /** Returns every resource type for the caller's hospital; types never reported show as 0/0 and stale. */
    @Transactional(readOnly = true)
    public List<ResourceDto> mine(AuthUser user) {
        Long hid = requireHospital(user);
        Map<ResourceType, HospitalResource> existing = new EnumMap<>(ResourceType.class);
        resources.findByHospital_Id(hid).forEach(r -> existing.put(r.getType(), r));
        List<ResourceDto> out = new ArrayList<>();
        for (ResourceType t : ResourceType.values()) {
            HospitalResource r = existing.get(t);
            out.add(r != null ? mapper.toDto(r) : new ResourceDto(t, t.label(), t.unit(), 0, 0, null, null, true));
        }
        return out;
    }

    @Transactional
    public List<ResourceDto> update(AuthUser user, ResourceBatchRequest req) {
        Long hid = requireHospital(user);
        Hospital hospital = hospitals.findById(hid).orElseThrow(() -> ApiException.notFound("Hospital"));
        Set<ResourceType> seen = EnumSet.noneOf(ResourceType.class);
        for (ResourceUpdateRequest u : req.updates()) {
            if (!seen.add(u.type())) throw ApiException.badRequest("Resource " + u.type().label() + " appears more than once.");
            if (u.available() > u.total()) {
                throw ApiException.badRequest(u.type().label() + ": available (" + u.available() + ") cannot exceed total (" + u.total() + ").");
            }
        }
        StringBuilder changes = new StringBuilder();
        for (ResourceUpdateRequest u : req.updates()) {
            HospitalResource r = resources.findByHospital_IdAndType(hid, u.type()).orElseGet(() -> {
                HospitalResource n = new HospitalResource();
                n.setHospital(hospital);
                n.setType(u.type());
                return n;
            });
            Integer oldTotal = r.getId() == null ? null : r.getTotal();
            Integer oldAvailable = r.getId() == null ? null : r.getAvailable();
            r.setTotal(u.total());
            r.setAvailable(u.available());
            r.setUpdatedAt(Instant.now());
            r.setUpdatedBy(user.fullName());
            resources.save(r);
            if (oldTotal == null || oldTotal != u.total() || oldAvailable != u.available()) {
                com.emergencylink.domain.ResourceChange c = new com.emergencylink.domain.ResourceChange();
                c.setHospital(hospital);
                c.setResourceType(u.type());
                c.setOldTotal(oldTotal);
                c.setOldAvailable(oldAvailable);
                c.setNewTotal(u.total());
                c.setNewAvailable(u.available());
                c.setChangedBy(user.fullName());
                c.setReason("Updated by the hospital");
                resourceChanges.save(c);
            }
            changes.append(u.type().label()).append(' ').append(u.available()).append('/').append(u.total()).append("; ");
        }
        audit.record(user, "RESOURCES_UPDATED", "Hospital", hid, hospital.getName(), changes.toString());
        return mine(user);
    }

    private Long requireHospital(AuthUser user) {
        if (user.hospitalId() == null) throw ApiException.badRequest("Your account is not linked to a hospital.");
        return user.hospitalId();
    }
}
