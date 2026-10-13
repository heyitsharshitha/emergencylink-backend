package com.emergencylink.service;

import com.emergencylink.domain.AuditEvent;
import com.emergencylink.repository.AuditEventRepository;
import com.emergencylink.security.AuthUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Appends audit events. The application only ever inserts: no code path updates or deletes an audit row.
 * Never receives passwords, tokens or secrets.
 */
@Service
public class AuditService {
    private static final Logger log = LoggerFactory.getLogger("audit");
    private final AuditEventRepository repo;
    public AuditService(AuditEventRepository repo) { this.repo = repo; }

    @Transactional
    public void record(AuthUser actor, String action, String entityType, Long entityId, String label, String detail) {
        write(actor, action, entityType, entityId, label, detail, null, "SUCCESS", null, null);
    }

    /** Sensitive change with the reason given by the actor and a before/after summary of the changed fields. */
    @Transactional
    public void record(AuthUser actor, String action, String entityType, Long entityId, String label, String detail,
                       String reason, String before, String after) {
        write(actor, action, entityType, entityId, label, detail, reason, "SUCCESS", before, after);
    }

    /** Records a refused privileged action in its own transaction so it survives the rollback of the failed request. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordRejected(AuthUser actor, String action, String entityType, Long entityId, String label, String detail, String reason) {
        write(actor, action, entityType, entityId, label, detail, reason, "REJECTED", null, null);
    }

    private void write(AuthUser actor, String action, String entityType, Long entityId, String label, String detail,
                       String reason, String outcome, String before, String after) {
        AuditEvent e = new AuditEvent();
        e.setActorEmail(actor == null ? "system" : actor.email());
        e.setActorRole(actor == null ? "SYSTEM" : actor.role().name());
        e.setAction(action);
        e.setEntityType(entityType);
        e.setEntityId(entityId);
        e.setEntityLabel(trim(label, 160));
        e.setDetail(trim(detail, 500));
        e.setReason(trim(reason, 500));
        e.setOutcome(outcome);
        e.setBeforeValue(trim(before, 1000));
        e.setAfterValue(trim(after, 1000));
        repo.save(e);
        log.info("action={} outcome={} actor={} entity={}#{}", action, outcome, e.getActorEmail(), entityType, entityId);
    }

    private static String trim(String s, int max) { return s == null ? null : (s.length() > max ? s.substring(0, max) : s); }
}
