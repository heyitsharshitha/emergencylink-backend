package com.emergencylink.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import java.time.Instant;

/** System-wide audit event (logins, resource updates, administrative actions, referral decisions). Never stores secrets. */
@Entity
@Table(name = "audit_events", indexes = @Index(name = "idx_audit_created", columnList = "created_at"))
@Getter @Setter @NoArgsConstructor
public class AuditEvent {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "actor_email", length = 160) private String actorEmail;
    @Column(name = "actor_role", length = 30) private String actorRole;
    @Column(nullable = false, length = 60) private String action;
    @Column(name = "entity_type", length = 40) private String entityType;
    @Column(name = "entity_id") private Long entityId;
    @Column(name = "entity_label", length = 160) private String entityLabel;
    @Column(length = 500) private String detail;
    @Column(length = 500) private String reason;
    @Column(length = 20) private String outcome;
    @Column(name = "before_value", length = 1000) private String beforeValue;
    @Column(name = "after_value", length = 1000) private String afterValue;
    @CreationTimestamp @Column(name = "created_at", updatable = false) private Instant createdAt;
}
