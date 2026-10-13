package com.emergencylink.service;

import com.emergencylink.exception.ApiException;
import com.emergencylink.security.AuthUser;
import com.emergencylink.domain.*;
import com.emergencylink.dto.AdminDtos.*;
import com.emergencylink.repository.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Read-only operational insight for administrators. Every figure and every exception is derived from the current
 * database records and the documented rules below; nothing is invented. Thresholds are operational settings, not clinical rules.
 */
@Service
public class AdminInsightService {
    private static final List<ReferralStatus> DONE = List.of(ReferralStatus.COMPLETED, ReferralStatus.CANCELLED);
    private static final Set<ReferralStatus> AWAITING = EnumSet.of(ReferralStatus.SENT, ReferralStatus.VIEWED, ReferralStatus.MORE_INFORMATION_REQUIRED);
    private static final Set<ReferralStatus> DISPATCHED = EnumSet.of(ReferralStatus.SENT, ReferralStatus.VIEWED,
            ReferralStatus.MORE_INFORMATION_REQUIRED, ReferralStatus.ACCEPTED, ReferralStatus.TRANSFER_IN_PROGRESS);
    private static final int MIN_RESPONSE_SAMPLE = 3;

    private final ReferralRepository referrals;
    private final HospitalRepository hospitals;
    private final UserRepository users;
    private final HospitalResourceRepository resources;
    private final HospitalMapper mapper;
    private final SettingsService settings;
    private final ExceptionStateRepository states;
    private final AuditService audit;

    public AdminInsightService(ReferralRepository referrals, HospitalRepository hospitals, UserRepository users,
                               HospitalResourceRepository resources, HospitalMapper mapper, SettingsService settings,
                               ExceptionStateRepository states, AuditService audit) {
        this.referrals = referrals;
        this.hospitals = hospitals;
        this.users = users;
        this.resources = resources;
        this.mapper = mapper;
        this.settings = settings;
        this.states = states;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public ExceptionSummaryDto exceptions() {
        Instant now = Instant.now();
        long waitThresholdMinutes = settings.waitMinutes();
        List<ExceptionDto> items = new ArrayList<>();
        Set<Long> receiving = new HashSet<>(users.findReceivingHospitalIds());

        // Rule 1: active hospitals with resource records that have not been confirmed recently.
        Map<Long, int[]> staleCount = new LinkedHashMap<>();
        Map<Long, Instant> oldest = new HashMap<>();
        Map<Long, String> names = new HashMap<>();
        for (HospitalResource res : resources.findAllActive()) {
            if (!mapper.isStale(res.getUpdatedAt())) continue;
            Long hid = res.getHospital().getId();
            staleCount.computeIfAbsent(hid, k -> new int[1])[0]++;
            names.put(hid, res.getHospital().getName());
            Instant u = res.getUpdatedAt();
            if (u != null && (oldest.get(hid) == null || u.isBefore(oldest.get(hid)))) oldest.put(hid, u);
        }
        long staleAfter = settings.staleMinutes();
        for (Map.Entry<Long, int[]> e : staleCount.entrySet()) {
            items.add(new ExceptionDto("stale-" + e.getKey(), "STALE_RESOURCE", "MEDIUM", "Resource availability needs confirmation",
                    e.getValue()[0] + " resource record(s) have not been updated in the last " + staleAfter
                            + " minutes. Other hospitals should confirm availability by phone before relying on them.",
                    "Hospital", e.getKey(), names.get(e.getKey()), oldest.getOrDefault(e.getKey(), now), "/app/hospitals/" + e.getKey(), "OPEN", null, null));
        }

        // Rule 2: active hospitals whose profile is missing contact, location or specialty information.
        for (Hospital h : hospitals.findAll()) {
            if (!h.isActive()) continue;
            List<String> missing = new ArrayList<>();
            if (blank(h.getPhone())) missing.add("phone");
            if (blank(h.getEmail())) missing.add("e-mail");
            if (blank(h.getAddress())) missing.add("address");
            if (blank(h.getEmergencyContact())) missing.add("emergency contact");
            if (h.getLatitude() == null || h.getLongitude() == null) missing.add("map coordinates");
            if (h.getSpecialties().isEmpty()) missing.add("specialties");
            if (!missing.isEmpty()) {
                items.add(new ExceptionDto("profile-" + h.getId(), "INCOMPLETE_PROFILE", "LOW", "Hospital profile is incomplete",
                        "Missing: " + String.join(", ", missing) + ".", "Hospital", h.getId(), h.getName(),
                        h.getUpdatedAt() == null ? now : h.getUpdatedAt(), "/app/hospitals/" + h.getId(), "OPEN", null, null));
            }
        }

        // Rule: active hospitals with no active user account cannot send, receive or update anything.
        Set<Long> withAccounts = new HashSet<>();
        for (User u : users.findAll()) if (u.isActive() && u.getHospital() != null) withAccounts.add(u.getHospital().getId());
        for (Hospital h : hospitals.findAll()) {
            if (h.isActive() && !withAccounts.contains(h.getId())) {
                items.add(new ExceptionDto("noaccount-" + h.getId(), "NO_ACCOUNT", "LOW", "Hospital has no active user account",
                        "Nobody can sign in for this hospital, so it cannot update resources or act on referrals.", "Hospital", h.getId(), h.getName(),
                        h.getUpdatedAt() == null ? now : h.getUpdatedAt(), "/app/hospitals/" + h.getId(), "OPEN", null, null));
            }
        }

        // Rules 3-6: open referrals with inconsistent assignment, inactive participants, long waits, or an administrator flag.
        for (Referral r : referrals.findOpen(DONE)) {
            Hospital target = r.getTargetHospital();
            String route = "/app/referrals/" + r.getId();
            Instant at = r.getUpdatedAt() == null ? now : r.getUpdatedAt();
            if (DISPATCHED.contains(r.getStatus())) {
                if (target == null) {
                    items.add(ex("noassign-" + r.getId(), "MISSING_ASSIGNMENT", "HIGH", "Sent referral has no receiving hospital",
                            "The referral is " + label(r.getStatus()) + " but no receiving hospital is recorded.", r, at, route));
                } else if (!target.isActive()) {
                    items.add(ex("inactive-" + r.getId(), "INACTIVE_HOSPITAL", "HIGH", "Receiving hospital is inactive",
                            target.getName() + " is deactivated but the referral is still " + label(r.getStatus()) + ". Consider reassigning it.", r, at, route));
                } else if (!receiving.contains(target.getId())) {
                    items.add(ex("noacct-" + r.getId(), "NO_RECEIVING_ACCOUNT", "HIGH", "Receiving hospital cannot act on this referral",
                            target.getName() + " has no active receiving-hospital account.", r, at, route));
                }
            }
            if (!r.getReferringHospital().isActive()) {
                items.add(ex("refinactive-" + r.getId(), "INACTIVE_HOSPITAL", "MEDIUM", "Referring hospital is inactive",
                        r.getReferringHospital().getName() + " is deactivated but the referral is still open.", r, at, route));
            }
            if (isOverdue(r, now, waitThresholdMinutes)) {
                long mins = Duration.between(r.getUpdatedAt(), now).toMinutes();
                boolean urgent = r.getUrgency() == Urgency.CRITICAL || r.getUrgency() == Urgency.URGENT;
                items.add(ex("overdue-" + r.getId(), "AWAITING_TOO_LONG", urgent ? "HIGH" : "MEDIUM", "Referral waiting beyond the threshold",
                        "Waiting " + mins + " minutes since the last update while " + label(r.getStatus()) + " (threshold " + waitThresholdMinutes
                                + " minutes). This is an operational threshold, not a clinical rule.", r, at, route));
            }
            if (r.isAdminFlagged()) {
                items.add(ex("flag-" + r.getId(), "FLAGGED_FOR_REVIEW", "MEDIUM", "Flagged for administrative review",
                        r.getFlagReason() == null ? "Flagged by an administrator." : r.getFlagReason(), r,
                        r.getFlaggedAt() == null ? at : r.getFlaggedAt(), route));
            }
        }

        Map<String, ExceptionState> stored = new HashMap<>();
        for (ExceptionState st : states.findAll()) stored.put(st.getKey(), st);
        List<ExceptionDto> merged = new ArrayList<>();
        for (ExceptionDto d : items) {
            ExceptionState st = stored.get(d.id());
            merged.add(st == null ? d : new ExceptionDto(d.id(), d.type(), d.severity(), d.title(), d.explanation(), d.entityType(), d.entityId(),
                    d.entityLabel(), d.detectedAt(), d.route(), st.getStatus(), st.getOwnerName(), st.getNote()));
        }
        merged.sort(Comparator.comparingInt((ExceptionDto d) -> "REVIEWED".equals(d.status()) ? 1 : 0)
                .thenComparingInt(d -> rank(d.severity())).thenComparing(ExceptionDto::detectedAt));
        List<ExceptionDto> open = merged.stream().filter(d -> !"REVIEWED".equals(d.status())).toList();
        int high = (int) open.stream().filter(d -> "HIGH".equals(d.severity())).count();
        int medium = (int) open.stream().filter(d -> "MEDIUM".equals(d.severity())).count();
        return new ExceptionSummaryDto(merged.size(), high, medium, open.size() - high - medium, merged.size() - open.size(), waitThresholdMinutes, staleAfter, merged);
    }

    /** Claims an exception (IN_PROGRESS), marks it reviewed with a note (REVIEWED), or reopens it (OPEN). */
    @Transactional
    public ExceptionSummaryDto setState(ExceptionStateRequest req, AuthUser admin) {
        ExceptionDto current = exceptions().items().stream().filter(d -> d.id().equals(req.id())).findFirst()
                .orElseThrow(() -> ApiException.notFound("Exception (it may already be resolved)"));
        String status = req.status().trim().toUpperCase(Locale.ROOT);
        String note = req.note() == null || req.note().isBlank() ? null : req.note().trim();
        switch (status) {
            case "OPEN" -> states.deleteById(req.id());
            case "IN_PROGRESS", "REVIEWED" -> {
                if ("REVIEWED".equals(status) && note == null) throw ApiException.badRequest("A note is required to mark an exception as reviewed.");
                ExceptionState st = states.findById(req.id()).orElseGet(() -> { ExceptionState n = new ExceptionState(); n.setKey(req.id()); return n; });
                st.setStatus(status);
                st.setOwnerName(admin.fullName());
                st.setOwnerEmail(admin.email());
                st.setNote(note);
                st.setUpdatedAt(Instant.now());
                states.save(st);
            }
            default -> throw ApiException.badRequest("Status must be OPEN, IN_PROGRESS or REVIEWED.");
        }
        audit.record(admin, "EXCEPTION_STATE_CHANGED", "Exception", current.entityId(), current.entityLabel(), current.title() + ": " + current.status() + " → " + status,
                note, current.status(), status);
        return exceptions();
    }

    @Transactional(readOnly = true)
    public AdminOverviewDto overview() {
        Instant now = Instant.now();
        long waitThresholdMinutes = settings.waitMinutes();
        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (ReferralStatus s : ReferralStatus.values()) byStatus.put(s.name(), 0L);
        for (Object[] row : referrals.countGroupedByStatus()) byStatus.put(((ReferralStatus) row[0]).name(), (Long) row[1]);
        Map<String, Long> byUrgency = new LinkedHashMap<>();
        for (Urgency u : Urgency.values()) byUrgency.put(u.name(), 0L);
        for (Object[] row : referrals.countGroupedByUrgency()) byUrgency.put(((Urgency) row[0]).name(), (Long) row[1]);

        List<Referral> open = referrals.findOpen(DONE);
        long awaiting = open.stream().filter(r -> AWAITING.contains(r.getStatus())).count();
        long urgent = open.stream().filter(r -> r.getStatus() != ReferralStatus.DECLINED
                && (r.getUrgency() == Urgency.CRITICAL || r.getUrgency() == Urgency.URGENT)).count();
        long overdue = open.stream().filter(r -> isOverdue(r, now, waitThresholdMinutes)).count();
        long flagged = open.stream().filter(Referral::isAdminFlagged).count();
        long stale = resources.findAllActive().stream().filter(x -> mapper.isStale(x.getUpdatedAt())).count();

        Specification<Referral> decided = (root, q, cb) -> cb.isNotNull(root.get("decidedAt"));
        List<Referral> decidedList = referrals.findAll(decided);
        Double avg = null;
        if (decidedList.size() >= MIN_RESPONSE_SAMPLE) {
            avg = decidedList.stream().filter(r -> r.getCreatedAt() != null && r.getDecidedAt() != null)
                    .mapToLong(r -> Duration.between(r.getCreatedAt(), r.getDecidedAt()).toMinutes()).average().orElse(0.0);
        }
        long usersActive = users.findAll().stream().filter(x -> x.isActive()).count();
        long done = byStatus.get(ReferralStatus.COMPLETED.name()) + byStatus.get(ReferralStatus.CANCELLED.name());
        long total = byStatus.values().stream().mapToLong(Long::longValue).sum();
        return new AdminOverviewDto(hospitals.count(), hospitals.countByActiveTrue(), users.count(), usersActive,
                total - done, awaiting, urgent, byStatus.get(ReferralStatus.COMPLETED.name()),
                byStatus.get(ReferralStatus.DECLINED.name()) + byStatus.get(ReferralStatus.CANCELLED.name()),
                flagged, overdue, stale, exceptions().total() - exceptions().reviewed(), byStatus, byUrgency, avg, decidedList.size(), waitThresholdMinutes, now);
    }

    private static boolean isOverdue(Referral r, Instant now, long waitThresholdMinutes) {
        return AWAITING.contains(r.getStatus()) && r.getUpdatedAt() != null
                && Duration.between(r.getUpdatedAt(), now).toMinutes() >= waitThresholdMinutes;
    }

    private static ExceptionDto ex(String id, String type, String severity, String title, String explanation, Referral r, Instant at, String route) {
        return new ExceptionDto(id, type, severity, title, explanation, "Referral", r.getId(), r.getReferralCode(), at, route, "OPEN", null, null);
    }

    private static int rank(String severity) { return "HIGH".equals(severity) ? 0 : "MEDIUM".equals(severity) ? 1 : 2; }
    private static boolean blank(String s) { return s == null || s.isBlank(); }
    private static String label(ReferralStatus s) { return s.name().toLowerCase().replace('_', ' '); }
}
