package com.emergencylink.service;

import com.emergencylink.domain.*;
import com.emergencylink.dto.ReferralDtos.MatchCheckDto;
import com.emergencylink.dto.ReferralDtos.MatchResultDto;
import com.emergencylink.repository.HospitalRepository;
import com.emergencylink.repository.HospitalResourceRepository;
import com.emergencylink.util.Geo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Deterministic, rule-based compatibility scoring. No machine learning and no clinical judgement:
 * each check awards points when a stated requirement is met by data the hospital has published.
 *
 * Weights: specialty 30, facilities 20, ICU 20, bed 15, resources 10, emergency service 5.
 * Checks that do not apply to a referral are excluded, so the score is earned / applicable.
 * UNKNOWN (missing or stale data) earns 50% of the weight and is flagged for confirmation.
 */
@Service
public class MatchingService {
    public static final String MATCHED = "MATCHED", UNAVAILABLE = "UNAVAILABLE", UNKNOWN = "UNKNOWN", NOT_REQUIRED = "NOT_REQUIRED";

    private final HospitalRepository hospitals;
    private final HospitalResourceRepository resourceRepo;
    private final com.emergencylink.repository.UserRepository users;
    private final HospitalMapper mapper;

    public MatchingService(HospitalRepository hospitals, HospitalResourceRepository resourceRepo,
                           com.emergencylink.repository.UserRepository users, HospitalMapper mapper) {
        this.hospitals = hospitals;
        this.resourceRepo = resourceRepo;
        this.users = users;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public List<MatchResultDto> match(Referral referral) {
        Long referringId = referral.getReferringHospital().getId();
        Hospital origin = referral.getReferringHospital();
        // Only active hospitals that have a receiving-hospital account can act on a referral.
        Set<Long> receiving = new HashSet<>(users.findReceivingHospitalIds());
        List<Hospital> candidates = hospitals.findByActiveTrue().stream()
                .filter(h -> !h.getId().equals(referringId) && receiving.contains(h.getId())).toList();
        Map<Long, Map<ResourceType, HospitalResource>> res = new HashMap<>();
        if (!candidates.isEmpty()) {
            for (HospitalResource r : resourceRepo.findByHospitalIds(candidates.stream().map(Hospital::getId).toList())) {
                res.computeIfAbsent(r.getHospital().getId(), k -> new EnumMap<>(ResourceType.class)).put(r.getType(), r);
            }
        }
        List<MatchResultDto> out = new ArrayList<>();
        for (Hospital h : candidates) {
            out.add(evaluate(referral, h, res.getOrDefault(h.getId(), Map.of()), origin));
        }
        out.sort(Comparator.comparingInt(MatchResultDto::score).reversed()
                .thenComparing(m -> m.distanceKm() == null ? Double.MAX_VALUE : m.distanceKm()));
        return out;
    }

    private MatchResultDto evaluate(Referral r, Hospital h, Map<ResourceType, HospitalResource> res, Hospital origin) {
        List<MatchCheckDto> checks = new ArrayList<>();

        // 1. Specialty
        String specName = r.getRequiredSpecialty() == null ? null : r.getRequiredSpecialty().getName();
        if (specName == null) {
            checks.add(new MatchCheckDto("specialty", "Required specialty", NOT_REQUIRED, 0, 0, "No specialty specified."));
        } else {
            boolean has = h.getSpecialties().stream().anyMatch(s -> s.getName().equalsIgnoreCase(specName));
            checks.add(new MatchCheckDto("specialty", specName + " specialty", has ? MATCHED : UNAVAILABLE, 30, has ? 30 : 0,
                    has ? specName + " is listed for this hospital." : specName + " is not listed for this hospital."));
        }

        // 2. Facilities
        Set<Facility> needed = r.getRequiredFacilities();
        if (needed.isEmpty()) {
            checks.add(new MatchCheckDto("facilities", "Required facilities", NOT_REQUIRED, 0, 0, "No specific facilities required."));
        } else {
            Set<String> have = h.getFacilities().stream().map(f -> f.getName().toLowerCase()).collect(Collectors.toSet());
            List<String> missing = needed.stream().map(Facility::getName).filter(n -> !have.contains(n.toLowerCase())).sorted().toList();
            int earned = (int) Math.round(20.0 * (needed.size() - missing.size()) / needed.size());
            checks.add(new MatchCheckDto("facilities", "Required facilities",
                    missing.isEmpty() ? MATCHED : UNAVAILABLE, 20, earned,
                    missing.isEmpty() ? "All " + needed.size() + " required facilities are listed."
                            : "Not listed: " + String.join(", ", missing) + "."));
        }

        // 3. ICU
        if (r.isIcuRequired()) {
            checks.add(resourceCheck("icu", "ICU bed", 20, res.get(ResourceType.ICU_BED), 1));
        } else {
            checks.add(new MatchCheckDto("icu", "ICU bed", NOT_REQUIRED, 0, 0, "ICU not requested."));
        }

        // 4. Bed (emergency bed preferred, general bed accepted)
        HospitalResource emergency = res.get(ResourceType.EMERGENCY_BED);
        HospitalResource general = res.get(ResourceType.GENERAL_BED);
        HospitalResource bedBasis = (emergency != null && emergency.getAvailable() > 0) ? emergency
                : (general != null && general.getAvailable() > 0) ? general : (emergency != null ? emergency : general);
        checks.add(resourceCheck("bed", "Emergency / general bed", 15, bedBasis, 1));

        // 5. Other resources (ventilator, oxygen, blood, explicit quantities)
        Map<ResourceType, Integer> required = new EnumMap<>(ResourceType.class);
        if (r.isVentilatorRequired()) required.merge(ResourceType.VENTILATOR, 1, Math::max);
        if (r.isOxygenRequired()) required.merge(ResourceType.OXYGEN_POINTS, 1, Math::max);
        if (r.isBloodRequired()) required.merge(ResourceType.BLOOD_UNITS, 1, Math::max);
        for (ReferralRequirement q : r.getRequirements()) required.merge(q.getResourceType(), q.getQuantity(), Math::max);
        required.remove(ResourceType.ICU_BED); // ICU already scored above
        if (required.isEmpty()) {
            checks.add(new MatchCheckDto("resources", "Other resources", NOT_REQUIRED, 0, 0, "No additional resources requested."));
        } else {
            int matched = 0, unknown = 0;
            List<String> notes = new ArrayList<>();
            for (var e : required.entrySet()) {
                MatchCheckDto c = resourceCheck("x", e.getKey().label(), 10, res.get(e.getKey()), e.getValue());
                if (MATCHED.equals(c.status())) matched++;
                else if (UNKNOWN.equals(c.status())) unknown++;
                notes.add(e.getKey().label() + " (" + e.getValue() + "): " + c.status().toLowerCase().replace('_', ' '));
            }
            int n = required.size();
            double frac = (matched + 0.5 * unknown) / n;
            String status = matched == n ? MATCHED : (matched + unknown == n ? UNKNOWN : UNAVAILABLE);
            checks.add(new MatchCheckDto("resources", "Other resources", status, 10, (int) Math.round(10 * frac), String.join("; ", notes) + "."));
        }

        // 6. Emergency service
        checks.add(new MatchCheckDto("emergency", "Emergency service", h.isEmergencyServices() ? MATCHED : UNAVAILABLE, 5,
                h.isEmergencyServices() ? 5 : 0, h.isEmergencyServices() ? "Emergency services are listed." : "Emergency services are not listed."));

        int applicable = checks.stream().filter(c -> !NOT_REQUIRED.equals(c.status())).mapToInt(MatchCheckDto::weight).sum();
        int earned = checks.stream().filter(c -> !NOT_REQUIRED.equals(c.status())).mapToInt(MatchCheckDto::earned).sum();
        int score = applicable == 0 ? 0 : (int) Math.round(100.0 * earned / applicable);

        Instant updated = res.values().stream().map(HospitalResource::getUpdatedAt).max(Instant::compareTo).orElse(null);
        Double km = Geo.haversineKm(origin.getLatitude(), origin.getLongitude(), h.getLatitude(), h.getLongitude());
        List<String> summary = new ArrayList<>();
        for (MatchCheckDto c : checks) {
            if (MATCHED.equals(c.status())) summary.add("✓ " + c.label());
            else if (UNAVAILABLE.equals(c.status())) summary.add("✗ " + c.label() + " — " + c.detail());
            else if (UNKNOWN.equals(c.status())) summary.add("⚠ " + c.label() + " — needs confirmation");
        }
        return new MatchResultDto(h.getId(), h.getName(), h.getCity(), score, km,
                km == null ? "Distance unavailable (no coordinates)" : "Estimated straight-line distance on simulated coordinates",
                h.isEmergencyServices(), updated, mapper.isStale(updated), checks, summary);
    }

    /** Scores a single countable resource against a needed quantity, honouring data freshness. */
    private MatchCheckDto resourceCheck(String key, String label, int weight, HospitalResource r, int quantity) {
        if (r == null) {
            return new MatchCheckDto(key, label, UNKNOWN, weight, weight / 2, "No availability has been published for this resource.");
        }
        boolean stale = mapper.isStale(r.getUpdatedAt());
        if (r.getAvailable() < quantity) {
            return new MatchCheckDto(key, label, UNAVAILABLE, weight, 0, r.getAvailable() + " available, " + quantity + " needed.");
        }
        if (stale) {
            return new MatchCheckDto(key, label, UNKNOWN, weight, weight / 2,
                    r.getAvailable() + " reported available, but the data is stale. Confirm with the hospital.");
        }
        return new MatchCheckDto(key, label, MATCHED, weight, weight, r.getAvailable() + " available (needs " + quantity + ").");
    }
}
