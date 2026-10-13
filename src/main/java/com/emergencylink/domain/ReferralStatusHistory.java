package com.emergencylink.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import java.time.Instant;

/** Timeline / audit record of every important referral event. */
@Entity
@Table(name = "referral_status_history", indexes = @Index(name = "idx_history_referral", columnList = "referral_id, created_at"))
@Getter @Setter @NoArgsConstructor
public class ReferralStatusHistory {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "referral_id")
    private Referral referral;
    @Enumerated(EnumType.STRING) @Column(name = "from_status", length = 30) private ReferralStatus fromStatus;
    @Enumerated(EnumType.STRING) @Column(name = "to_status", nullable = false, length = 30) private ReferralStatus toStatus;
    @Column(name = "event_type", nullable = false, length = 40) private String eventType;
    @Column(length = 1000) private String note;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "actor_id") private User actor;
    @Column(name = "actor_label", length = 200) private String actorLabel;
    @CreationTimestamp @Column(name = "created_at", updatable = false) private Instant createdAt;
}
