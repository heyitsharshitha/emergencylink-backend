package com.emergencylink.service;

import com.emergencylink.domain.*;
import com.emergencylink.dto.CommonDtos.ActivityDto;
import com.emergencylink.dto.DashboardDtos.*;
import com.emergencylink.dto.HospitalDtos.ResourceDto;
import com.emergencylink.repository.*;
import com.emergencylink.security.AuthUser;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;

/** Role-specific dashboard data computed from real referral, resource and audit records. */
@Service
public class DashboardService {
    private static final Set<ReferralStatus> PENDING = EnumSet.of(ReferralStatus.CREATED, ReferralStatus.MATCHING, ReferralStatus.SENT,
            ReferralStatus.VIEWED, ReferralStatus.MORE_INFORMATION_REQUIRED);
    private static final Set<ReferralStatus> ACCEPTED = EnumSet.of(ReferralStatus.ACCEPTED, ReferralStatus.TRANSFER_IN_PROGRESS);
    private static final Set<ReferralStatus> CLOSED = EnumSet.of(ReferralStatus.COMPLETED, ReferralStatus.CANCELLED, ReferralStatus.DECLINED);

    private final ReferralRepository referrals;
    private final ReferralStatusHistoryRepository history;
    private final HospitalRepository hospitals;
    private final HospitalResourceRepository resources;
    private final UserRepository users;
    private final ResourceService resourceService;
    private final HospitalMapper mapper;

    public DashboardService(ReferralRepository referrals, ReferralStatusHistoryRepository history, HospitalRepository hospitals,
                            HospitalResourceRepository resources, UserRepository users, ResourceService resourceService, HospitalMapper mapper) {
        this.referrals = referrals;
        this.history = history;
        this.hospitals = hospitals;
        this.resources = resources;
        this.users = users;
        this.resourceService = resourceService;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public DashboardDto dashboard(AuthUser u) {
        Specification<Referral> scope = (root, q, cb) -> switch (u.role()) {
            case REFERRING_HOSPITAL -> cb.equal(root.get("referringHospital").get("id"), u.hospitalId());
            case RECEIVING_HOSPITAL -> cb.and(cb.equal(root.get("targetHospital").get("id"), u.hospitalId()),
                    cb.not(root.get("status").in(ReferralStatus.CREATED, ReferralStatus.MATCHING)));
            case ADMIN -> cb.conjunction();
        };
        List<Referral> list = referrals.findAll(scope);

        Map<String, Long> kpis = new LinkedHashMap<>();
        kpis.put("total", (long) list.size());
        kpis.put("active", count(list, r -> !CLOSED.contains(r.getStatus())));
        kpis.put("pending", count(list, r -> PENDING.contains(r.getStatus())));
        kpis.put("incoming", count(list, r -> r.getStatus() == ReferralStatus.SENT));
        kpis.put("awaitingDecision", count(list, r -> r.getStatus() == ReferralStatus.VIEWED || r.getStatus() == ReferralStatus.MORE_INFORMATION_REQUIRED
                || r.getStatus() == ReferralStatus.SENT));
        kpis.put("accepted", count(list, r -> ACCEPTED.contains(r.getStatus())));
        kpis.put("declined", count(list, r -> r.getStatus() == ReferralStatus.DECLINED));
        kpis.put("completed", count(list, r -> r.getStatus() == ReferralStatus.COMPLETED));
        kpis.put("urgent", count(list, r -> r.getUrgency() != Urgency.STANDARD && !CLOSED.contains(r.getStatus())));
        if (u.isAdmin()) {
            kpis.put("hospitals", (long) hospitals.count());
            kpis.put("activeHospitals", hospitals.countByActiveTrue());
            kpis.put("users", users.count());
        }

        Map<String, Long> status = new LinkedHashMap<>();
        for (ReferralStatus s : ReferralStatus.values()) status.put(s.name(), count(list, r -> r.getStatus() == s));
        Map<String, Long> urgency = new LinkedHashMap<>();
        for (Urgency x : Urgency.values()) urgency.put(x.name(), count(list, r -> r.getUrgency() == x));

        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        Map<LocalDate, Long> byDay = list.stream().collect(Collectors.groupingBy(r -> r.getCreatedAt().atZone(ZoneOffset.UTC).toLocalDate(), Collectors.counting()));
        List<DayCount> over = new ArrayList<>();
        for (int i = 13; i >= 0; i--) {
            LocalDate d = today.minusDays(i);
            over.add(new DayCount(d.toString(), byDay.getOrDefault(d, 0L)));
        }

        List<SpecialtyCount> specs = list.stream().filter(r -> r.getRequiredSpecialty() != null)
                .collect(Collectors.groupingBy(r -> r.getRequiredSpecialty().getName(), Collectors.counting()))
                .entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed()).limit(6)
                .map(e -> new SpecialtyCount(e.getKey(), e.getValue())).toList();

        // Average time from creation to the receiving hospital's decision (accept/decline), in minutes.
        OptionalDouble avg = list.stream().filter(r -> r.getDecidedAt() != null)
                .mapToLong(r -> Duration.between(r.getCreatedAt(), r.getDecidedAt()).toMinutes()).average();

        List<ReferralStatusHistory> events = u.isAdmin() ? history.recentAll(PageRequest.of(0, 12))
                : history.recentForHospital(u.hospitalId(), PageRequest.of(0, 24));
        List<ActivityDto> activity = events.stream()
                .filter(h -> u.role() != Role.RECEIVING_HOSPITAL || !h.getToStatus().isPreDispatch())
                .limit(10)
                .map(h -> new ActivityDto(h.getReferral().getId(), h.getReferral().getReferralCode(), h.getEventType(),
                        h.getNote() != null ? h.getNote() : h.getEventType(), h.getActorLabel(), h.getCreatedAt()))
                .toList();

        List<ResourceDto> own = u.isAdmin() ? List.of() : resourceService.mine(u);
        List<ResourceTotal> overview = u.isAdmin() ? resourceOverview() : List.of();
        List<HospitalActivity> hospAct = u.isAdmin() ? hospitalActivity(list) : List.of();

        return new DashboardDto(u.role().name(), kpis, status, urgency, over, specs,
                avg.isPresent() ? Math.round(avg.getAsDouble() * 10.0) / 10.0 : null, activity, own, overview, hospAct, Instant.now());
    }

    private List<ResourceTotal> resourceOverview() {
        Map<ResourceType, List<HospitalResource>> by = resources.findAllActive().stream().collect(Collectors.groupingBy(HospitalResource::getType));
        List<ResourceTotal> out = new ArrayList<>();
        for (ResourceType t : ResourceType.values()) {
            List<HospitalResource> l = by.getOrDefault(t, List.of());
            out.add(new ResourceTotal(t.name(), t.label(), t.unit(), l.stream().mapToLong(HospitalResource::getTotal).sum(),
                    l.stream().mapToLong(HospitalResource::getAvailable).sum(), l.size(),
                    l.stream().filter(r -> mapper.isStale(r.getUpdatedAt())).count()));
        }
        return out;
    }

    private List<HospitalActivity> hospitalActivity(List<Referral> list) {
        Map<Long, long[]> counts = new HashMap<>();
        Map<Long, String> names = new HashMap<>();
        for (Referral r : list) {
            for (Hospital h : new Hospital[]{r.getReferringHospital(), r.getTargetHospital()}) {
                if (h == null) continue;
                counts.computeIfAbsent(h.getId(), k -> new long[1])[0]++;
                names.put(h.getId(), h.getName());
            }
        }
        return counts.entrySet().stream().map(e -> new HospitalActivity(e.getKey(), names.get(e.getKey()), e.getValue()[0]))
                .sorted(Comparator.comparingLong(HospitalActivity::referrals).reversed()).limit(8).toList();
    }

    private static long count(List<Referral> l, java.util.function.Predicate<Referral> p) { return l.stream().filter(p).count(); }
}
