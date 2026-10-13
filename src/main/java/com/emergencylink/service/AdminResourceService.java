package com.emergencylink.service;

import com.emergencylink.domain.*;
import com.emergencylink.dto.AdminDtos.*;
import com.emergencylink.dto.HospitalDtos.ResourceDto;
import com.emergencylink.dto.HospitalDtos.ResourceUpdateRequest;
import com.emergencylink.exception.ApiException;
import com.emergencylink.repository.HospitalRepository;
import com.emergencylink.repository.HospitalResourceRepository;
import com.emergencylink.repository.ResourceChangeRepository;
import com.emergencylink.security.AuthUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;

/** Administrator resource corrections. Every change is recorded in the resource history and the audit log with its reason. */
@Service
public class AdminResourceService {
    private final HospitalRepository hospitals;
    private final HospitalResourceRepository resources;
    private final ResourceChangeRepository changes;
    private final HospitalMapper mapper;
    private final NotificationService notifications;
    private final AuditService audit;

    public AdminResourceService(HospitalRepository hospitals, HospitalResourceRepository resources, ResourceChangeRepository changes,
                                HospitalMapper mapper, NotificationService notifications, AuditService audit) {
        this.hospitals = hospitals;
        this.resources = resources;
        this.changes = changes;
        this.mapper = mapper;
        this.notifications = notifications;
        this.audit = audit;
    }

    /** Every resource type for the hospital; types never reported show as 0/0 and stale. */
    @Transactional(readOnly = true)
    public List<ResourceDto> current(Long hospitalId) {
        requireHospital(hospitalId);
        Map<ResourceType, HospitalResource> existing = new EnumMap<>(ResourceType.class);
        resources.findByHospital_Id(hospitalId).forEach(r -> existing.put(r.getType(), r));
        List<ResourceDto> out = new ArrayList<>();
        for (ResourceType t : ResourceType.values()) {
            HospitalResource r = existing.get(t);
            out.add(r != null ? mapper.toDto(r) : new ResourceDto(t, t.label(), t.unit(), 0, 0, null, null, true));
        }
        return out;
    }

    @Transactional
    public ResourceCorrectionResult correct(Long hospitalId, ResourceCorrectionRequest req, AuthUser admin) {
        Hospital hospital = requireHospital(hospitalId);
        Set<ResourceType> seen = EnumSet.noneOf(ResourceType.class);
        for (ResourceUpdateRequest u : req.updates()) {
            if (!seen.add(u.type())) throw ApiException.badRequest("Resource " + u.type().label() + " appears more than once.");
            if (u.available() > u.total()) {
                throw ApiException.badRequest(u.type().label() + ": available (" + u.available() + ") cannot exceed total (" + u.total() + ").");
            }
        }
        String reason = req.reason().trim();
        String actorLabel = admin.fullName() + " (administrator correction)";
        List<String> warnings = new ArrayList<>();
        StringBuilder before = new StringBuilder();
        StringBuilder after = new StringBuilder();
        int changed = 0;
        for (ResourceUpdateRequest u : req.updates()) {
            HospitalResource r = resources.findByHospital_IdAndType(hospitalId, u.type()).orElse(null);
            Integer oldTotal = r == null ? null : r.getTotal();
            Integer oldAvailable = r == null ? null : r.getAvailable();
            if (r != null && oldTotal == u.total() && oldAvailable == u.available()) continue;
            if (r == null) {
                r = new HospitalResource();
                r.setHospital(hospital);
                r.setType(u.type());
            }
            r.setTotal(u.total());
            r.setAvailable(u.available());
            r.setUpdatedAt(Instant.now());
            r.setUpdatedBy(actorLabel);
            resources.save(r);
            ResourceChange c = new ResourceChange();
            c.setHospital(hospital);
            c.setResourceType(u.type());
            c.setOldTotal(oldTotal);
            c.setOldAvailable(oldAvailable);
            c.setNewTotal(u.total());
            c.setNewAvailable(u.available());
            c.setChangedBy(actorLabel);
            c.setReason(reason);
            changes.save(c);
            before.append(u.type().label()).append(' ').append(oldAvailable == null ? "unknown" : oldAvailable + "/" + oldTotal).append("; ");
            after.append(u.type().label()).append(' ').append(u.available()).append('/').append(u.total()).append("; ");
            if (oldAvailable != null && oldAvailable > 0 && u.available() == 0) {
                warnings.add(u.type().label() + " now shows none available, so " + hospital.getName() + " may no longer match referrals that need it.");
            } else if ((oldAvailable == null || oldAvailable == 0) && u.available() > 0) {
                warnings.add(u.type().label() + " is now available, so matching results for " + hospital.getName() + " may improve.");
            }
            changed++;
        }
        if (changed == 0) throw ApiException.badRequest("No changes were made. Change at least one value.");
        audit.record(admin, "RESOURCES_ADMIN_CORRECTED", "Hospital", hospitalId, hospital.getName(), changed + " resource record(s) corrected",
                reason, before.toString(), after.toString());
        notifications.notifyHospital(hospital, null, "RESOURCE_UPDATED", "Resource availability corrected by an administrator",
                "Please review your resource figures. Reason: " + reason, admin.id());
        return new ResourceCorrectionResult(current(hospitalId), warnings);
    }

    @Transactional(readOnly = true)
    public List<ResourceChangeDto> history(Long hospitalId) {
        requireHospital(hospitalId);
        return changes.findTop100ByHospital_IdOrderByCreatedAtDescIdDesc(hospitalId).stream()
                .map(c -> new ResourceChangeDto(c.getId(), c.getResourceType().name(), c.getResourceType().label(), c.getOldTotal(), c.getOldAvailable(),
                        c.getNewTotal(), c.getNewAvailable(), c.getChangedBy(), c.getReason(), c.getCreatedAt())).toList();
    }

    private Hospital requireHospital(Long id) {
        return hospitals.findById(id).orElseThrow(() -> ApiException.notFound("Hospital"));
    }
}
