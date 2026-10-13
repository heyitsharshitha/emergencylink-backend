package com.emergencylink.service;

import com.emergencylink.domain.*;
import com.emergencylink.dto.CommonDtos.PageResponse;
import com.emergencylink.dto.ReferralDtos.*;
import com.emergencylink.exception.ApiException;
import com.emergencylink.exception.InvalidTransitionException;
import com.emergencylink.repository.*;
import com.emergencylink.security.AuthUser;
import jakarta.persistence.criteria.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;

/**
 * Core referral workflow: creation, rule-based matching, dispatch, receiving-hospital decisions,
 * transfer tracking, messaging and the audit timeline. The backend is the authority on who may do what.
 */
@Service
public class ReferralService {
    private final ReferralRepository referrals;
    private final HospitalRepository hospitals;
    private final UserRepository users;
    private final ReferralStatusHistoryRepository history;
    private final ReferralMessageRepository messages;
    private final CatalogService catalog;
    private final MatchingService matching;
    private final NotificationService notifications;
    private final AuditService audit;

    @Value("${app.thresholds.referral-wait-minutes:30}")
    private long waitThresholdMinutes;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private SettingsService settings;

    public ReferralService(ReferralRepository referrals, HospitalRepository hospitals, UserRepository users,
                           ReferralStatusHistoryRepository history, ReferralMessageRepository messages, CatalogService catalog,
                           MatchingService matching, NotificationService notifications, AuditService audit) {
        this.referrals = referrals;
        this.hospitals = hospitals;
        this.users = users;
        this.history = history;
        this.messages = messages;
        this.catalog = catalog;
        this.matching = matching;
        this.notifications = notifications;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ create

    @Transactional
    public ReferralDto create(ReferralCreateRequest req, AuthUser u) {
        if (u.role() != Role.REFERRING_HOSPITAL || u.hospitalId() == null) {
            throw ApiException.forbidden("Only referring hospital accounts can create referrals.");
        }
        Hospital own = hospitals.findById(u.hospitalId()).orElseThrow(() -> ApiException.notFound("Hospital"));
        Referral r = new Referral();
        r.setPatientName(req.patientName().trim());
        r.setAge(req.age());
        r.setGender(req.gender());
        r.setEmergencyCondition(req.emergencyCondition().trim());
        r.setClinicalSummary(req.clinicalSummary());
        r.setUrgency(req.urgency());
        r.setRequiredSpecialty(catalog.requireSpecialty(req.requiredSpecialty()));
        r.getRequiredFacilities().addAll(catalog.requireFacilities(req.facilities()));
        r.setIcuRequired(req.icuRequired());
        r.setVentilatorRequired(req.ventilatorRequired());
        r.setOxygenRequired(req.oxygenRequired());
        r.setBloodRequired(req.bloodRequired());
        r.setBloodGroup(req.bloodRequired() ? req.bloodGroup() : null);
        r.setAdditionalRequirements(req.additionalRequirements());
        r.setReferringHospital(own);
        r.setCreatedBy(users.getReferenceById(u.id()));
        if (req.resources() != null) {
            Set<ResourceType> seen = EnumSet.noneOf(ResourceType.class);
            for (ResourceRequirementDto q : req.resources()) {
                if (!seen.add(q.type())) continue;
                ReferralRequirement rr = new ReferralRequirement();
                rr.setReferral(r);
                rr.setResourceType(q.type());
                rr.setQuantity(q.quantity());
                r.getRequirements().add(rr);
            }
        }
        if (req.targetHospitalId() != null) {
            Hospital target = hospitals.findById(req.targetHospitalId()).orElseThrow(() -> ApiException.badRequest("Preferred hospital was not found."));
            if (!target.isActive() || target.getId().equals(own.getId())) throw ApiException.badRequest("Choose an active hospital other than your own.");
            requireCanReceive(target);
            r.setTargetHospital(target);
        }
        r.setReferralCode("TMP-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24));
        r = referrals.saveAndFlush(r);
        r.setReferralCode(String.format("EL-%d-%05d", LocalDate.now(ZoneOffset.UTC).getYear(), r.getId()));
        addHistory(r, null, ReferralStatus.CREATED, "CREATED", "Referral created" +
                (r.getTargetHospital() != null ? " with preferred hospital " + r.getTargetHospital().getName() : ""), u);
        audit.record(u, "REFERRAL_CREATED", "Referral", r.getId(), r.getReferralCode(), r.getUrgency() + " · " + r.getEmergencyCondition());
        return toDto(r, u);
    }

    // ------------------------------------------------------------------ read

    @Transactional(readOnly = true)
    public PageResponse<ReferralDto> list(AuthUser u, String q, ReferralStatus status, Urgency urgency, String specialty,
                                          String hospital, LocalDate from, LocalDate to, String view, String sort, String dir,
                                          int page, int size) {
        Specification<Referral> spec = (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            if (u.role() == Role.REFERRING_HOSPITAL) {
                p.add(cb.equal(root.get("referringHospital").get("id"), u.hospitalId()));
            } else if (u.role() == Role.RECEIVING_HOSPITAL) {
                p.add(cb.equal(root.get("targetHospital").get("id"), u.hospitalId()));
                p.add(cb.not(root.get("status").in(ReferralStatus.CREATED, ReferralStatus.MATCHING)));
            }
            if (q != null && !q.isBlank()) {
                String like = "%" + q.trim().toLowerCase() + "%";
                p.add(cb.or(cb.like(cb.lower(root.get("referralCode")), like), cb.like(cb.lower(root.get("patientName")), like),
                        cb.like(cb.lower(root.get("emergencyCondition")), like)));
            }
            if (status != null) p.add(cb.equal(root.get("status"), status));
            if (urgency != null) p.add(cb.equal(root.get("urgency"), urgency));
            if (specialty != null && !specialty.isBlank()) {
                Join<Referral, Specialty> sj = root.join("requiredSpecialty", JoinType.LEFT);
                p.add(cb.equal(cb.lower(sj.get("name")), specialty.trim().toLowerCase()));
            }
            if (hospital != null && !hospital.isBlank()) {
                String like = "%" + hospital.trim().toLowerCase() + "%";
                Join<Referral, Hospital> rj = root.join("referringHospital", JoinType.LEFT);
                Join<Referral, Hospital> tj = root.join("targetHospital", JoinType.LEFT);
                p.add(cb.or(cb.like(cb.lower(rj.get("name")), like), cb.like(cb.lower(tj.get("name")), like)));
            }
            if (from != null) p.add(cb.greaterThanOrEqualTo(root.<Instant>get("createdAt"), from.atStartOfDay(ZoneOffset.UTC).toInstant()));
            if (to != null) p.add(cb.lessThan(root.<Instant>get("createdAt"), to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()));
            if ("urgent".equals(view)) {
                p.add(root.get("urgency").in(Urgency.CRITICAL, Urgency.URGENT));
                p.add(cb.not(root.get("status").in(ReferralStatus.COMPLETED, ReferralStatus.CANCELLED, ReferralStatus.DECLINED)));
            } else if ("awaiting".equals(view)) {
                p.add(root.get("status").in(ReferralStatus.SENT, ReferralStatus.VIEWED, ReferralStatus.MORE_INFORMATION_REQUIRED));
            } else if ("flagged".equals(view) && u.role() == Role.ADMIN) {
                p.add(cb.isTrue(root.<Boolean>get("adminFlagged")));
            } else if ("overdue".equals(view)) {
                p.add(root.get("status").in(ReferralStatus.SENT, ReferralStatus.VIEWED, ReferralStatus.MORE_INFORMATION_REQUIRED));
                p.add(cb.lessThan(root.<Instant>get("updatedAt"), Instant.now().minus(settings != null ? settings.waitMinutes() : waitThresholdMinutes, ChronoUnit.MINUTES)));
            } else if ("recent".equals(view)) {
                p.add(cb.greaterThanOrEqualTo(root.<Instant>get("updatedAt"), Instant.now().minus(48, ChronoUnit.HOURS)));
            }
            boolean isCount = query.getResultType() == Long.class || query.getResultType() == long.class;
            if (!isCount && "priority".equals(sort)) {
                Expression<Integer> rank = cb.<Integer>selectCase()
                        .when(cb.equal(root.get("urgency"), Urgency.CRITICAL), 0)
                        .when(cb.equal(root.get("urgency"), Urgency.URGENT), 1)
                        .otherwise(2);
                query.orderBy(cb.asc(rank), cb.desc(root.get("updatedAt")));
            }
            return cb.and(p.toArray(new Predicate[0]));
        };
        Pageable pageable;
        int safePage = Math.max(page, 0), safeSize = Math.min(Math.max(size, 1), 100);
        if ("priority".equals(sort)) {
            pageable = PageRequest.of(safePage, safeSize);
        } else {
            Set<String> allowed = Set.of("createdAt", "updatedAt", "referralCode", "status");
            String field = allowed.contains(sort) ? sort : "updatedAt";
            Sort.Direction d = "asc".equalsIgnoreCase(dir) ? Sort.Direction.ASC : Sort.Direction.DESC;
            pageable = PageRequest.of(safePage, safeSize, Sort.by(d, field));
        }
        Page<Referral> result = referrals.findAll(spec, pageable);
        return PageResponse.of(result.map(r -> toDto(r, u)));
    }

    @Transactional(readOnly = true)
    public ReferralDto get(Long id, AuthUser u) {
        return toDto(load(id, u), u);
    }

    @Transactional(readOnly = true)
    public List<TimelineEventDto> timeline(Long id, AuthUser u) {
        load(id, u);
        return history.findByReferral_IdOrderByCreatedAtAscIdAsc(id).stream()
                .map(h -> new TimelineEventDto(h.getId(), h.getEventType(), h.getFromStatus(), h.getToStatus(), h.getNote(), h.getActorLabel(), h.getCreatedAt()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<MatchResultDto> previewMatches(Long id, AuthUser u) {
        Referral r = load(id, u);
        if (u.role() == Role.RECEIVING_HOSPITAL) throw ApiException.forbidden("Matching results are available to the referring hospital and administrators.");
        return matching.match(r);
    }

    // ------------------------------------------------------------------ referring hospital actions

    @Transactional
    public List<MatchResultDto> runMatching(Long id, AuthUser u) {
        Referral r = load(id, u);
        requireReferringOwner(r, u);
        ReferralStatus s = r.getStatus();
        if (s != ReferralStatus.CREATED && s != ReferralStatus.MATCHING && s != ReferralStatus.DECLINED) {
            throw new InvalidTransitionException(s, ReferralStatus.MATCHING);
        }
        List<MatchResultDto> results = matching.match(r);
        String note = "Rule-based matching completed against " + results.size() + " hospitals" +
                (results.isEmpty() ? "" : "; top compatibility " + results.get(0).score() + "% (" + results.get(0).hospitalName() + ")");
        if (s == ReferralStatus.MATCHING) {
            addHistory(r, s, s, "MATCHING_COMPLETED", note + " (re-run)", u);
        } else {
            if (s == ReferralStatus.DECLINED) r.setTargetHospital(null); // re-route to a new hospital
            transition(r, ReferralStatus.MATCHING, "MATCHING_COMPLETED", note, u);
        }
        return results;
    }

    @Transactional
    public ReferralDto send(Long id, SendRequest req, AuthUser u) {
        Referral r = load(id, u);
        requireReferringOwner(r, u);
        Hospital target = hospitals.findById(req.hospitalId()).orElseThrow(() -> ApiException.badRequest("Receiving hospital was not found."));
        if (!target.isActive() || target.getId().equals(r.getReferringHospital().getId())) {
            throw ApiException.badRequest("Choose an active hospital other than your own.");
        }
        requireCanReceive(target);
        if (!r.getStatus().canMoveTo(ReferralStatus.SENT)) throw new InvalidTransitionException(r.getStatus(), ReferralStatus.SENT);
        r.setTargetHospital(target);
        transition(r, ReferralStatus.SENT, "SENT", "Referral sent to " + target.getName() + suffix(req.note()), u);
        notifications.notifyHospital(target, r, "NEW_REFERRAL", "New " + r.getUrgency().name().toLowerCase() + " referral " + r.getReferralCode(),
                r.getEmergencyCondition() + " · from " + r.getReferringHospital().getName(), u.id());
        audit.record(u, "REFERRAL_SENT", "Referral", r.getId(), r.getReferralCode(), "Sent to " + target.getName());
        return toDto(r, u);
    }

    @Transactional
    public ReferralDto provideInformation(Long id, RequiredNoteRequest req, AuthUser u) {
        Referral r = load(id, u);
        requireReferringOwner(r, u);
        transition(r, ReferralStatus.VIEWED, "INFO_PROVIDED", "Additional information provided", u);
        saveMessage(r, req.note(), u);
        notifications.notifyHospital(r.getTargetHospital(), r, "INFO_PROVIDED", "Information provided for " + r.getReferralCode(), req.note(), u.id());
        return toDto(r, u);
    }

    @Transactional
    public ReferralDto cancel(Long id, NoteRequest req, AuthUser u) {
        Referral r = load(id, u);
        requireReferringOwner(r, u);
        boolean wasDispatched = !r.getStatus().isPreDispatch();
        transition(r, ReferralStatus.CANCELLED, "CANCELLED", "Referral cancelled" + suffix(req == null ? null : req.note()), u);
        if (r.getTargetHospital() != null && wasDispatched) {
            notifications.notifyHospital(r.getTargetHospital(), r, "STATUS_CHANGED", "Referral " + r.getReferralCode() + " was cancelled",
                    req == null ? null : req.note(), u.id());
        }
        audit.record(u, "REFERRAL_CANCELLED", "Referral", r.getId(), r.getReferralCode(), req == null ? null : req.note());
        return toDto(r, u);
    }

    // ------------------------------------------------------------------ receiving hospital actions

    @Transactional
    public ReferralDto markViewed(Long id, AuthUser u) {
        Referral r = load(id, u);
        requireTarget(r, u);
        if (r.getStatus() == ReferralStatus.SENT) {
            transition(r, ReferralStatus.VIEWED, "VIEWED", "Referral viewed by " + u.hospitalName(), u);
            notifications.notifyHospital(r.getReferringHospital(), r, "STATUS_CHANGED", r.getReferralCode() + " viewed",
                    u.hospitalName() + " has opened the referral.", u.id());
        }
        return toDto(r, u);
    }

    @Transactional
    public ReferralDto accept(Long id, NoteRequest req, AuthUser u) {
        Referral r = load(id, u);
        requireTarget(r, u);
        r.setDecisionReason(req == null ? null : req.note());
        r.setDecidedAt(Instant.now());
        transition(r, ReferralStatus.ACCEPTED, "ACCEPTED", "Referral accepted by " + u.hospitalName() + suffix(req == null ? null : req.note()), u);
        notifications.notifyHospital(r.getReferringHospital(), r, "REFERRAL_ACCEPTED", r.getReferralCode() + " accepted",
                u.hospitalName() + " accepted the referral.", u.id());
        audit.record(u, "REFERRAL_ACCEPTED", "Referral", r.getId(), r.getReferralCode(), req == null ? null : req.note());
        return toDto(r, u);
    }

    @Transactional
    public ReferralDto decline(Long id, RequiredNoteRequest req, AuthUser u) {
        Referral r = load(id, u);
        requireTarget(r, u);
        r.setDecisionReason(req.note());
        r.setDecidedAt(Instant.now());
        transition(r, ReferralStatus.DECLINED, "DECLINED", "Referral declined by " + u.hospitalName() + ": " + req.note(), u);
        notifications.notifyHospital(r.getReferringHospital(), r, "REFERRAL_DECLINED", r.getReferralCode() + " declined",
                u.hospitalName() + ": " + req.note(), u.id());
        audit.record(u, "REFERRAL_DECLINED", "Referral", r.getId(), r.getReferralCode(), req.note());
        return toDto(r, u);
    }

    @Transactional
    public ReferralDto requestInformation(Long id, RequiredNoteRequest req, AuthUser u) {
        Referral r = load(id, u);
        requireTarget(r, u);
        transition(r, ReferralStatus.MORE_INFORMATION_REQUIRED, "INFO_REQUESTED", "More information requested: " + req.note(), u);
        saveMessage(r, "Information requested: " + req.note(), u);
        notifications.notifyHospital(r.getReferringHospital(), r, "INFO_REQUESTED", "More information requested for " + r.getReferralCode(),
                req.note(), u.id());
        return toDto(r, u);
    }

    // ------------------------------------------------------------------ either party

    @Transactional
    public ReferralDto startTransfer(Long id, AuthUser u) {
        Referral r = load(id, u);
        requireParty(r, u);
        transition(r, ReferralStatus.TRANSFER_IN_PROGRESS, "TRANSFER_STARTED", "Patient transfer started", u);
        notifyCounterpart(r, u, "STATUS_CHANGED", r.getReferralCode() + " transfer in progress", "Transfer has started.");
        return toDto(r, u);
    }

    @Transactional
    public ReferralDto complete(Long id, AuthUser u) {
        Referral r = load(id, u);
        requireParty(r, u);
        transition(r, ReferralStatus.COMPLETED, "COMPLETED", "Transfer completed", u);
        notifyCounterpart(r, u, "STATUS_CHANGED", r.getReferralCode() + " completed", "The referral is complete.");
        audit.record(u, "REFERRAL_COMPLETED", "Referral", r.getId(), r.getReferralCode(), null);
        return toDto(r, u);
    }

    // ------------------------------------------------------------------ messages

    @Transactional(readOnly = true)
    public List<MessageDto> messages(Long id, AuthUser u) {
        load(id, u);
        return messages.findByReferral_IdOrderByCreatedAtAscIdAsc(id).stream().map(this::toDto).toList();
    }

    @Transactional
    public MessageDto postMessage(Long id, MessageRequest req, AuthUser u) {
        Referral r = load(id, u);
        requireParty(r, u);
        if (r.getStatus().isPreDispatch() || r.getTargetHospital() == null) {
            throw ApiException.conflict("Messaging opens once the referral has been sent to a hospital.");
        }
        ReferralMessage m = saveMessage(r, req.body(), u);
        notifyCounterpart(r, u, "NEW_MESSAGE", "New message on " + r.getReferralCode(), u.fullName() + ": " + req.body());
        return toDto(m);
    }

    // ------------------------------------------------------------------ internals

    private Referral load(Long id, AuthUser u) {
        Referral r = referrals.findById(id).orElseThrow(() -> ApiException.notFound("Referral"));
        boolean ok = switch (u.role()) {
            case ADMIN -> true;
            case REFERRING_HOSPITAL -> r.getReferringHospital().getId().equals(u.hospitalId());
            case RECEIVING_HOSPITAL -> r.getTargetHospital() != null && r.getTargetHospital().getId().equals(u.hospitalId())
                    && !r.getStatus().isPreDispatch();
        };
        if (!ok) throw ApiException.notFound("Referral"); // do not reveal that the record exists
        return r;
    }

    private void requireCanReceive(Hospital target) {
        if (!users.findReceivingHospitalIds().contains(target.getId())) {
            throw ApiException.badRequest(target.getName() + " does not have a receiving-hospital account and cannot accept referrals.");
        }
    }

    private boolean isOwner(Referral r, AuthUser u) {
        return u.role() == Role.REFERRING_HOSPITAL && r.getReferringHospital().getId().equals(u.hospitalId());
    }

    private boolean isTarget(Referral r, AuthUser u) {
        return u.role() == Role.RECEIVING_HOSPITAL && r.getTargetHospital() != null && r.getTargetHospital().getId().equals(u.hospitalId());
    }

    private void requireReferringOwner(Referral r, AuthUser u) {
        if (!isOwner(r, u)) throw ApiException.forbidden("Only the referring hospital can perform this action.");
    }

    private void requireTarget(Referral r, AuthUser u) {
        if (!isTarget(r, u)) throw ApiException.forbidden("Only the receiving hospital can perform this action.");
    }

    private void requireParty(Referral r, AuthUser u) {
        if (!isOwner(r, u) && !isTarget(r, u)) throw ApiException.forbidden("Only the hospitals involved in this referral can perform this action.");
    }

    private void transition(Referral r, ReferralStatus to, String event, String note, AuthUser u) {
        ReferralStatus from = r.getStatus();
        if (!from.canMoveTo(to)) throw new InvalidTransitionException(from, to);
        r.setStatus(to);
        addHistory(r, from, to, event, note, u);
    }

    private void addHistory(Referral r, ReferralStatus from, ReferralStatus to, String event, String note, AuthUser u) {
        ReferralStatusHistory h = new ReferralStatusHistory();
        h.setReferral(r);
        h.setFromStatus(from);
        h.setToStatus(to);
        h.setEventType(event);
        h.setNote(note != null && note.length() > 1000 ? note.substring(0, 1000) : note);
        h.setActor(users.getReferenceById(u.id()));
        h.setActorLabel(u.fullName() + (u.hospitalName() != null ? " · " + u.hospitalName() : ""));
        history.save(h);
        r.setUpdatedAt(Instant.now());
    }

    private ReferralMessage saveMessage(Referral r, String body, AuthUser u) {
        ReferralMessage m = new ReferralMessage();
        m.setReferral(r);
        m.setSender(users.getReferenceById(u.id()));
        m.setSenderHospital(u.hospitalId() == null ? null : hospitals.getReferenceById(u.hospitalId()));
        m.setBody(body);
        return messages.save(m);
    }

    private void notifyCounterpart(Referral r, AuthUser u, String type, String title, String message) {
        Hospital counterpart = isOwner(r, u) ? r.getTargetHospital() : r.getReferringHospital();
        notifications.notifyHospital(counterpart, r, type, title, message, u.id());
    }

    private static String suffix(String note) { return note == null || note.isBlank() ? "" : " — " + note.trim(); }

    // ------------------------------------------------------------------ mapping

    List<String> allowedActions(Referral r, AuthUser u) {
        List<String> a = new ArrayList<>();
        ReferralStatus s = r.getStatus();
        if (isOwner(r, u)) {
            switch (s) {
                case CREATED -> { a.add("MATCH"); a.add("SEND"); a.add("CANCEL"); }
                case MATCHING -> { a.add("MATCH"); a.add("SEND"); a.add("CANCEL"); }
                case SENT, VIEWED -> a.add("CANCEL");
                case MORE_INFORMATION_REQUIRED -> { a.add("PROVIDE_INFO"); a.add("CANCEL"); }
                case ACCEPTED -> { a.add("START_TRANSFER"); a.add("CANCEL"); }
                case DECLINED -> a.add("MATCH");
                case TRANSFER_IN_PROGRESS -> a.add("COMPLETE");
                default -> { }
            }
            if (!s.isPreDispatch() && r.getTargetHospital() != null) a.add("MESSAGE");
        } else if (isTarget(r, u)) {
            switch (s) {
                case SENT -> a.add("VIEW");
                case VIEWED -> { a.add("ACCEPT"); a.add("DECLINE"); a.add("REQUEST_INFO"); }
                case MORE_INFORMATION_REQUIRED -> { a.add("ACCEPT"); a.add("DECLINE"); }
                case ACCEPTED -> a.add("START_TRANSFER");
                case TRANSFER_IN_PROGRESS -> a.add("COMPLETE");
                default -> { }
            }
            a.add("MESSAGE");
        }
        return a;
    }

    private static HospitalRef ref(Hospital h) {
        return h == null ? null : new HospitalRef(h.getId(), h.getName(), h.getCity(), h.getPhone(), h.getEmergencyContact());
    }

    ReferralDto toDto(Referral r, AuthUser u) {
        return new ReferralDto(r.getId(), r.getReferralCode(), r.getPatientName(), r.getAge(), r.getGender(), r.getEmergencyCondition(),
                r.getClinicalSummary(), r.getUrgency(), r.getRequiredSpecialty() == null ? null : r.getRequiredSpecialty().getName(),
                r.getRequiredFacilities().stream().map(Facility::getName).sorted().toList(),
                r.getRequirements().stream().map(q -> new ResourceRequirementDto(q.getResourceType(), q.getQuantity())).toList(),
                r.isIcuRequired(), r.isVentilatorRequired(), r.isOxygenRequired(), r.isBloodRequired(), r.getBloodGroup(),
                r.getAdditionalRequirements(), ref(r.getReferringHospital()), ref(r.getTargetHospital()), r.getStatus(),
                allowedActions(r, u), r.getDecisionReason(), r.getDecidedAt(),
                r.getCreatedBy() == null ? null : r.getCreatedBy().getFullName(), r.getCreatedAt(), r.getUpdatedAt(),
                u.role() == Role.ADMIN && r.isAdminFlagged(), u.role() == Role.ADMIN ? r.getFlagReason() : null);
    }

    private MessageDto toDto(ReferralMessage m) {
        User s = m.getSender();
        Hospital h = m.getSenderHospital();
        return new MessageDto(m.getId(), m.getReferral().getId(), m.getBody(), s.getId(), s.getFullName(), s.getRole().name(),
                h == null ? null : h.getId(), h == null ? null : h.getName(), m.getCreatedAt());
    }
}
