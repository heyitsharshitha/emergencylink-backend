package com.emergencylink.service;

import com.emergencylink.domain.*;
import com.emergencylink.dto.AdminDtos.*;
import com.emergencylink.dto.ReferralDtos.ReferralDto;
import com.emergencylink.exception.ApiException;
import com.emergencylink.exception.InvalidTransitionException;
import com.emergencylink.repository.*;
import com.emergencylink.security.AuthUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Privileged referral corrections for administrators. These are explicit workflows, not arbitrary edits:
 * each one is limited by referral status, requires a reason, writes an append-only audit event, and keeps the
 * business timeline and messages intact. Internal notes and flags are never visible to hospitals.
 */
@Service
public class AdminReferralService {
    /** Statuses in which urgency, specialty and requirement text may still be corrected. */
    private static final Set<ReferralStatus> EDITABLE = EnumSet.of(ReferralStatus.CREATED, ReferralStatus.MATCHING,
            ReferralStatus.SENT, ReferralStatus.VIEWED, ReferralStatus.MORE_INFORMATION_REQUIRED, ReferralStatus.DECLINED);
    /** Statuses in which an administrator may re-route a dispatched referral to a different hospital. */
    private static final Set<ReferralStatus> REASSIGNABLE = EnumSet.of(ReferralStatus.SENT, ReferralStatus.VIEWED,
            ReferralStatus.MORE_INFORMATION_REQUIRED, ReferralStatus.ACCEPTED, ReferralStatus.DECLINED);

    private final ReferralRepository referrals;
    private final HospitalRepository hospitals;
    private final UserRepository users;
    private final ReferralStatusHistoryRepository history;
    private final AdminNoteRepository notes;
    private final CatalogService catalog;
    private final NotificationService notifications;
    private final AuditService audit;
    private final ReferralService referralService;

    public AdminReferralService(ReferralRepository referrals, HospitalRepository hospitals, UserRepository users,
                                ReferralStatusHistoryRepository history, AdminNoteRepository notes, CatalogService catalog,
                                NotificationService notifications, AuditService audit, ReferralService referralService) {
        this.referrals = referrals;
        this.hospitals = hospitals;
        this.users = users;
        this.history = history;
        this.notes = notes;
        this.catalog = catalog;
        this.notifications = notifications;
        this.audit = audit;
        this.referralService = referralService;
    }

    // ------------------------------------------------------------------ edit

    @Transactional
    public ReferralDto edit(Long id, ReferralEditRequest req, AuthUser admin) {
        Referral r = load(id);
        if (!EDITABLE.contains(r.getStatus())) {
            audit.recordRejected(admin, "REFERRAL_ADMIN_EDITED", "Referral", r.getId(), r.getReferralCode(), "Edit refused: status " + r.getStatus(), req.reason());
            throw ApiException.conflict("Fields cannot be edited while the referral is " + r.getStatus().name().toLowerCase().replace('_', ' ')
                    + ". Use reassign or cancel, which notify the hospitals involved.");
        }
        String beforeText = afterText(r);
        List<String> changes = new ArrayList<>();
        if (req.urgency() != null && req.urgency() != r.getUrgency()) {
            changes.add("urgency " + r.getUrgency() + " → " + req.urgency());
            r.setUrgency(req.urgency());
        }
        if (req.requiredSpecialty() != null && !req.requiredSpecialty().isBlank()) {
            Specialty sp = catalog.requireSpecialty(req.requiredSpecialty());
            String before = r.getRequiredSpecialty() == null ? "none" : r.getRequiredSpecialty().getName();
            if (r.getRequiredSpecialty() == null || !Objects.equals(r.getRequiredSpecialty().getId(), sp.getId())) {
                changes.add("specialty " + before + " → " + sp.getName());
                r.setRequiredSpecialty(sp);
            }
        }
        if (req.additionalRequirements() != null) {
            String next = req.additionalRequirements().isBlank() ? null : req.additionalRequirements().trim();
            if (!Objects.equals(next, r.getAdditionalRequirements())) {
                changes.add("additional requirements updated");
                r.setAdditionalRequirements(next);
            }
        }
        if (changes.isEmpty()) throw ApiException.badRequest("No changes were made. Change at least one field.");

        String summary = String.join("; ", changes);
        addHistory(r, r.getStatus(), r.getStatus(), "ADMIN_EDITED", "Administrator correction: " + summary + ". Reason: " + req.reason().trim(), admin);
        audit.record(admin, "REFERRAL_ADMIN_EDITED", "Referral", r.getId(), r.getReferralCode(), summary, req.reason().trim(), beforeText, afterText(r));
        notifyParties(r, admin, "STATUS_CHANGED", r.getReferralCode() + " details corrected by an administrator", summary);
        return referralService.toDto(r, admin);
    }

    // ------------------------------------------------------------------ reassign

    @Transactional
    public ReferralDto reassign(Long id, ReassignRequest req, AuthUser admin) {
        Referral r = load(id);
        ReferralStatus from = r.getStatus();
        if (!REASSIGNABLE.contains(from)) {
            audit.recordRejected(admin, "REFERRAL_REASSIGNED", "Referral", r.getId(), r.getReferralCode(), "Reassignment refused: status " + from, req.reason());
            throw ApiException.conflict(from.isPreDispatch()
                    ? "This referral has not been sent yet. The referring hospital chooses the receiving hospital when it sends."
                    : "A referral that is " + from.name().toLowerCase().replace('_', ' ') + " cannot be reassigned.");
        }
        Hospital next = hospitals.findById(req.hospitalId()).orElseThrow(() -> ApiException.badRequest("The chosen hospital was not found."));
        if (!next.isActive()) throw ApiException.badRequest(next.getName() + " is inactive and cannot receive referrals.");
        if (next.getId().equals(r.getReferringHospital().getId())) throw ApiException.badRequest("A referral cannot be reassigned to the hospital that sent it.");
        if (r.getTargetHospital() != null && next.getId().equals(r.getTargetHospital().getId())) {
            throw ApiException.badRequest("That hospital is already assigned to this referral.");
        }
        if (!users.findReceivingHospitalIds().contains(next.getId())) {
            throw ApiException.badRequest(next.getName() + " does not have a receiving-hospital account.");
        }
        Hospital previous = r.getTargetHospital();
        r.setTargetHospital(next);
        r.setStatus(ReferralStatus.SENT);
        r.setDecidedAt(null);
        r.setDecisionReason(null);
        String note = "Administrator reassigned the referral from " + (previous == null ? "no hospital" : previous.getName()) + " to "
                + next.getName() + " (was " + from + "). Reason: " + req.reason().trim();
        addHistory(r, from, ReferralStatus.SENT, "ADMIN_REASSIGNED", note, admin);
        audit.record(admin, "REFERRAL_REASSIGNED", "Referral", r.getId(), r.getReferralCode(),
                (previous == null ? "none" : previous.getName()) + " → " + next.getName(), req.reason().trim(),
                "hospital=" + (previous == null ? "none" : previous.getName()) + "; status=" + from, "hospital=" + next.getName() + "; status=SENT");
        if (previous != null) {
            notifications.notifyHospital(previous, r, "STATUS_CHANGED", r.getReferralCode() + " was reassigned",
                    "An administrator moved this referral to another hospital. Reason: " + req.reason().trim(), admin.id());
        }
        notifications.notifyHospital(next, r, "NEW_REFERRAL", "New " + r.getUrgency().name().toLowerCase() + " referral " + r.getReferralCode(),
                r.getEmergencyCondition() + " · from " + r.getReferringHospital().getName() + " (reassigned by an administrator)", admin.id());
        notifications.notifyHospital(r.getReferringHospital(), r, "STATUS_CHANGED", r.getReferralCode() + " reassigned to " + next.getName(),
                "Reason: " + req.reason().trim(), admin.id());
        return referralService.toDto(r, admin);
    }

    // ------------------------------------------------------------------ cancel

    @Transactional
    public ReferralDto cancel(Long id, ReasonRequest req, AuthUser admin) {
        Referral r = load(id);
        ReferralStatus from = r.getStatus();
        if (!from.canMoveTo(ReferralStatus.CANCELLED)) {
            audit.recordRejected(admin, "REFERRAL_ADMIN_CANCELLED", "Referral", r.getId(), r.getReferralCode(), "Cancellation refused: status " + from, req.reason());
            throw new InvalidTransitionException(from, ReferralStatus.CANCELLED);
        }
        r.setStatus(ReferralStatus.CANCELLED);
        addHistory(r, from, ReferralStatus.CANCELLED, "ADMIN_CANCELLED", "Cancelled by an administrator. Reason: " + req.reason().trim(), admin);
        audit.record(admin, "REFERRAL_ADMIN_CANCELLED", "Referral", r.getId(), r.getReferralCode(), "from " + from + " | reason: " + req.reason().trim());
        notifyParties(r, admin, "STATUS_CHANGED", "Referral " + r.getReferralCode() + " was cancelled by an administrator", req.reason().trim());
        return referralService.toDto(r, admin);
    }

    // ------------------------------------------------------------------ flags and notes (internal only)

    @Transactional
    public ReferralDto flag(Long id, ReasonRequest req, AuthUser admin) {
        Referral r = load(id);
        if (r.isAdminFlagged()) throw ApiException.conflict("This referral is already flagged for review.");
        r.setAdminFlagged(true);
        r.setFlagReason(req.reason().trim());
        r.setFlaggedAt(java.time.Instant.now());
        saveNote(r, "Flagged for review: " + req.reason().trim(), admin);
        audit.record(admin, "REFERRAL_FLAGGED", "Referral", r.getId(), r.getReferralCode(), req.reason().trim());
        return referralService.toDto(r, admin);
    }

    @Transactional
    public ReferralDto resolveFlag(Long id, ReasonRequest req, AuthUser admin) {
        Referral r = load(id);
        if (!r.isAdminFlagged()) throw ApiException.conflict("This referral is not flagged.");
        r.setAdminFlagged(false);
        r.setFlagReason(null);
        r.setFlaggedAt(null);
        saveNote(r, "Flag resolved: " + req.reason().trim(), admin);
        audit.record(admin, "REFERRAL_FLAG_RESOLVED", "Referral", r.getId(), r.getReferralCode(), req.reason().trim());
        return referralService.toDto(r, admin);
    }

    @Transactional(readOnly = true)
    public List<AdminNoteDto> notes(Long id) {
        load(id);
        return notes.findByReferral_IdOrderByCreatedAtAscIdAsc(id).stream()
                .map(n -> new AdminNoteDto(n.getId(), n.getBody(), n.getAuthorLabel(), n.getCreatedAt())).toList();
    }

    @Transactional
    public AdminNoteDto addNote(Long id, AdminNoteRequest req, AuthUser admin) {
        Referral r = load(id);
        AdminNote n = saveNote(r, req.body().trim(), admin);
        audit.record(admin, "ADMIN_NOTE_ADDED", "Referral", r.getId(), r.getReferralCode(), "Internal note added");
        return new AdminNoteDto(n.getId(), n.getBody(), n.getAuthorLabel(), n.getCreatedAt());
    }

    // ------------------------------------------------------------------ internals

    private static String afterText(Referral r) {
        return "urgency=" + r.getUrgency() + "; specialty=" + (r.getRequiredSpecialty() == null ? "none" : r.getRequiredSpecialty().getName())
                + "; additionalRequirements=" + (r.getAdditionalRequirements() == null ? "" : r.getAdditionalRequirements());
    }

    private Referral load(Long id) {
        return referrals.findById(id).orElseThrow(() -> ApiException.notFound("Referral"));
    }

    private AdminNote saveNote(Referral r, String body, AuthUser admin) {
        AdminNote n = new AdminNote();
        n.setReferral(r);
        n.setAuthor(users.getReferenceById(admin.id()));
        n.setAuthorLabel(admin.fullName());
        n.setBody(body);
        return notes.save(n);
    }

    private void addHistory(Referral r, ReferralStatus from, ReferralStatus to, String event, String note, AuthUser admin) {
        ReferralStatusHistory h = new ReferralStatusHistory();
        h.setReferral(r);
        h.setFromStatus(from);
        h.setToStatus(to);
        h.setEventType(event);
        h.setNote(note != null && note.length() > 1000 ? note.substring(0, 1000) : note);
        h.setActor(users.getReferenceById(admin.id()));
        h.setActorLabel(admin.fullName() + " · Administrator");
        history.save(h);
        r.setUpdatedAt(java.time.Instant.now());
    }

    /** Tells the referring hospital and, once the referral has been dispatched, the receiving hospital. */
    private void notifyParties(Referral r, AuthUser admin, String type, String title, String message) {
        notifications.notifyHospital(r.getReferringHospital(), r, type, title, message, admin.id());
        if (!r.getStatus().isPreDispatch() || r.getStatus() == ReferralStatus.CANCELLED) {
            notifications.notifyHospital(r.getTargetHospital(), r, type, title, message, admin.id());
        }
    }
}
