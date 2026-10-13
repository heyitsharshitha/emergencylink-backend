package com.emergencylink.service;

import com.emergencylink.domain.*;
import com.emergencylink.dto.AdminDtos.*;
import com.emergencylink.exception.ApiException;
import com.emergencylink.repository.*;
import com.emergencylink.security.AuthUser;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;

/**
 * Administrator reports computed from stored records for a date range. Exports never include patient identifiers or
 * clinical summaries. Rates are only reported when there is data to support them.
 */
@Service
public class AdminReportService {
    private static final int MAX_RANGE_DAYS = 366;
    private static final int MIN_SAMPLE = 3;
    private static final Set<ReferralStatus> ACCEPTED_STATES = EnumSet.of(ReferralStatus.ACCEPTED, ReferralStatus.TRANSFER_IN_PROGRESS, ReferralStatus.COMPLETED);

    private final ReferralRepository referrals;
    private final HospitalRepository hospitals;
    private final HospitalResourceRepository resources;
    private final ReferralStatusHistoryRepository history;
    private final AuditEventRepository auditRepo;
    private final HospitalMapper mapper;
    private final SettingsService settings;
    private final AuditService audit;

    public AdminReportService(ReferralRepository referrals, HospitalRepository hospitals, HospitalResourceRepository resources,
                              ReferralStatusHistoryRepository history, AuditEventRepository auditRepo, HospitalMapper mapper,
                              SettingsService settings, AuditService audit) {
        this.referrals = referrals;
        this.hospitals = hospitals;
        this.resources = resources;
        this.history = history;
        this.auditRepo = auditRepo;
        this.mapper = mapper;
        this.settings = settings;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public ReportSummaryDto summary(LocalDate fromIn, LocalDate toIn) {
        LocalDate[] range = range(fromIn, toIn);
        LocalDate from = range[0];
        LocalDate to = range[1];
        Instant start = from.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant end = to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();

        List<Referral> list = referrals.findAll(createdBetween(start, end));
        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (ReferralStatus s : ReferralStatus.values()) byStatus.put(s.name(), 0L);
        Map<String, Long> byUrgency = new LinkedHashMap<>();
        for (Urgency u : Urgency.values()) byUrgency.put(u.name(), 0L);
        Map<LocalDate, Long> perDay = new TreeMap<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) perDay.put(d, 0L);
        List<Long> minutes = new ArrayList<>();
        for (Referral r : list) {
            byStatus.merge(r.getStatus().name(), 1L, Long::sum);
            byUrgency.merge(r.getUrgency().name(), 1L, Long::sum);
            perDay.merge(r.getCreatedAt().atZone(ZoneOffset.UTC).toLocalDate(), 1L, Long::sum);
            if (r.getDecidedAt() != null) minutes.add(Duration.between(r.getCreatedAt(), r.getDecidedAt()).toMinutes());
        }
        List<DayCountDto> volume = new ArrayList<>();
        perDay.forEach((d, c) -> volume.add(new DayCountDto(d.toString(), c)));

        Map<String, Long> events = new HashMap<>();
        for (Object[] row : history.countEventsBetween(start, end)) events.put((String) row[0], (Long) row[1]);
        long accepted = events.getOrDefault("ACCEPTED", 0L);
        long declined = events.getOrDefault("DECLINED", 0L);
        Double acceptance = accepted + declined == 0 ? null : Math.round(accepted * 1000.0 / (accepted + declined)) / 10.0;

        ResponseTimeDto response = responseTime(minutes);
        Map<Long, long[]> activity = new HashMap<>();
        for (Referral r : list) {
            activity.computeIfAbsent(r.getReferringHospital().getId(), k -> new long[5])[0]++;
            if (r.getTargetHospital() != null) {
                long[] a = activity.computeIfAbsent(r.getTargetHospital().getId(), k -> new long[5]);
                a[1]++;
                if (ACCEPTED_STATES.contains(r.getStatus())) a[2]++;
                if (r.getStatus() == ReferralStatus.DECLINED) a[3]++;
                if (r.getStatus() == ReferralStatus.COMPLETED) a[4]++;
            }
        }
        List<Hospital> allHospitals = hospitals.findAll();
        List<HospitalActivityDto> hospitalActivity = new ArrayList<>();
        for (Hospital h : allHospitals) {
            long[] a = activity.get(h.getId());
            if (a == null) continue;
            hospitalActivity.add(new HospitalActivityDto(h.getId(), h.getName(), h.isActive(), a[0], a[1], a[2], a[3], a[4]));
        }
        hospitalActivity.sort(Comparator.comparingLong((HospitalActivityDto d) -> d.sent() + d.received()).reversed());

        Map<String, Long> auditByAction = new TreeMap<>();
        for (AuditEvent e : auditRepo.findAll(auditBetween(start, end))) auditByAction.merge(e.getAction(), 1L, Long::sum);

        return new ReportSummaryDto(from.toString(), to.toString(), Instant.now(), list.size(), byStatus, byUrgency, volume,
                accepted, declined, acceptance, events.getOrDefault("COMPLETED", 0L), events.getOrDefault("CANCELLED", 0L) + events.getOrDefault("ADMIN_CANCELLED", 0L),
                response, hospitalActivity, staleResources(), completeness(allHospitals), auditByAction, settings.staleMinutes());
    }

    /** CSV for the named report. Types: hospitals, hospital-activity, stale-resources, completeness, audit, volume. */
    @Transactional(readOnly = true)
    public String csv(String type, LocalDate fromIn, LocalDate toIn, AuthUser admin) {
        LocalDate[] range = range(fromIn, toIn);
        StringBuilder sb = new StringBuilder();
        sb.append("# EmergencyLink report: ").append(type).append('\n');
        sb.append("# Date range: ").append(range[0]).append(" to ").append(range[1]).append(" (UTC). Generated ").append(Instant.now()).append('\n');
        sb.append("# Demonstration data: fictional hospitals and referrals. Patient identifiers and clinical details are never exported.\n");
        switch (type) {
            case "hospitals" -> {
                sb.append("Hospital,Type,City,State,Active,Phone,Email,Specialties,Facilities\n");
                for (Hospital h : hospitals.findAll(Sort.by("name"))) {
                    sb.append(row(h.getName(), h.getType(), h.getCity(), h.getState(), h.isActive(), h.getPhone(), h.getEmail(),
                            String.join("; ", h.getSpecialties().stream().map(Specialty::getName).sorted().toList()),
                            String.join("; ", h.getFacilities().stream().map(Facility::getName).sorted().toList())));
                }
            }
            case "hospital-activity" -> {
                sb.append("Hospital,Active,Referrals sent,Referrals received,Currently accepted or beyond,Currently declined,Completed\n");
                for (HospitalActivityDto a : summary(range[0], range[1]).hospitalActivity()) {
                    sb.append(row(a.hospitalName(), a.active(), a.sent(), a.received(), a.accepted(), a.declined(), a.completed()));
                }
            }
            case "stale-resources" -> {
                sb.append("Hospital,Resource,Available,Total,Last updated,Age in minutes\n");
                for (StaleResourceDto s : staleResources()) {
                    sb.append(row(s.hospitalName(), s.type(), s.available(), s.total(), s.updatedAt(), s.ageMinutes()));
                }
            }
            case "completeness" -> {
                sb.append("Hospital,Profile complete (%),Missing\n");
                for (CompletenessDto c : completeness(hospitals.findAll())) sb.append(row(c.hospitalName(), c.percent(), String.join("; ", c.missing())));
            }
            case "volume" -> {
                sb.append("Date,Referrals created\n");
                for (DayCountDto d : summary(range[0], range[1]).volumeByDay()) sb.append(row(d.date(), d.count()));
            }
            case "audit" -> {
                sb.append("Time,Actor,Role,Action,Outcome,Entity,Record,Reason,Detail\n");
                Instant start = range[0].atStartOfDay(ZoneOffset.UTC).toInstant();
                Instant end = range[1].plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
                for (AuditEvent e : auditRepo.findAll(auditBetween(start, end), Sort.by(Sort.Direction.DESC, "createdAt", "id"))) {
                    sb.append(row(e.getCreatedAt(), e.getActorEmail(), e.getActorRole(), e.getAction(), e.getOutcome() == null ? "SUCCESS" : e.getOutcome(),
                            e.getEntityType(), e.getEntityLabel(), e.getReason(), e.getDetail()));
                }
            }
            default -> throw ApiException.badRequest("Unknown report type.");
        }
        audit.record(admin, "REPORT_EXPORTED", "Report", null, type, "CSV export " + range[0] + " to " + range[1]);
        return sb.toString();
    }

    // ------------------------------------------------------------------ pieces

    private List<StaleResourceDto> staleResources() {
        Instant now = Instant.now();
        List<StaleResourceDto> out = new ArrayList<>();
        for (HospitalResource r : resources.findAllActive()) {
            if (!mapper.isStale(r.getUpdatedAt())) continue;
            out.add(new StaleResourceDto(r.getHospital().getId(), r.getHospital().getName(), r.getType().label(), r.getAvailable(), r.getTotal(),
                    r.getUpdatedAt(), r.getUpdatedAt() == null ? null : Duration.between(r.getUpdatedAt(), now).toMinutes()));
        }
        out.sort(Comparator.comparing((StaleResourceDto s) -> s.hospitalName()).thenComparing(StaleResourceDto::type));
        return out;
    }

    private List<CompletenessDto> completeness(List<Hospital> list) {
        List<CompletenessDto> out = new ArrayList<>();
        for (Hospital h : list) {
            if (!h.isActive()) continue;
            List<String> missing = new ArrayList<>();
            if (blank(h.getPhone())) missing.add("phone");
            if (blank(h.getEmail())) missing.add("e-mail");
            if (blank(h.getAddress())) missing.add("address");
            if (blank(h.getEmergencyContact())) missing.add("emergency contact");
            if (h.getLatitude() == null || h.getLongitude() == null) missing.add("map coordinates");
            if (h.getSpecialties().isEmpty()) missing.add("specialties");
            out.add(new CompletenessDto(h.getId(), h.getName(), (6 - missing.size()) * 100 / 6, missing));
        }
        out.sort(Comparator.comparingInt(CompletenessDto::percent).thenComparing(CompletenessDto::hospitalName));
        return out;
    }

    private static ResponseTimeDto responseTime(List<Long> minutes) {
        if (minutes.size() < MIN_SAMPLE) return new ResponseTimeDto(minutes.size(), null, null, null, null);
        List<Long> sorted = new ArrayList<>(minutes);
        Collections.sort(sorted);
        int n = sorted.size();
        double median = n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
        double avg = sorted.stream().mapToLong(Long::longValue).average().orElse(0);
        return new ResponseTimeDto(n, avg, median, (double) sorted.get(0), (double) sorted.get(n - 1));
    }

    private static LocalDate[] range(LocalDate from, LocalDate to) {
        LocalDate end = to != null ? to : LocalDate.now(ZoneOffset.UTC);
        LocalDate begin = from != null ? from : end.minusDays(29);
        if (begin.isAfter(end)) throw ApiException.badRequest("The start date must not be after the end date.");
        if (Duration.between(begin.atStartOfDay(), end.plusDays(1).atStartOfDay()).toDays() > MAX_RANGE_DAYS) {
            throw ApiException.badRequest("Choose a range of at most " + MAX_RANGE_DAYS + " days.");
        }
        return new LocalDate[] {begin, end};
    }

    private static Specification<Referral> createdBetween(Instant start, Instant end) {
        return (root, q, cb) -> cb.and(cb.greaterThanOrEqualTo(root.<Instant>get("createdAt"), start), cb.lessThan(root.<Instant>get("createdAt"), end));
    }

    private static Specification<AuditEvent> auditBetween(Instant start, Instant end) {
        return (root, q, cb) -> {
            List<Predicate> p = new ArrayList<>();
            p.add(cb.greaterThanOrEqualTo(root.<Instant>get("createdAt"), start));
            p.add(cb.lessThan(root.<Instant>get("createdAt"), end));
            return cb.and(p.toArray(new Predicate[0]));
        };
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }

    private static String row(Object... cells) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(cell(cells[i]));
        }
        return sb.append('\n').toString();
    }

    /** Quotes a cell and neutralises spreadsheet formula injection. */
    private static String cell(Object o) {
        if (o == null) return "";
        String s = String.valueOf(o);
        if (!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0) s = "'" + s;
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }
}
