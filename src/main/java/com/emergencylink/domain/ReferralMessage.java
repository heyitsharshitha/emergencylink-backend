package com.emergencylink.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import java.time.Instant;

@Entity
@Table(name = "referral_messages", indexes = @Index(name = "idx_message_referral", columnList = "referral_id, created_at"))
@Getter @Setter @NoArgsConstructor
public class ReferralMessage {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "referral_id")
    private Referral referral;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "sender_id")
    private User sender;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "sender_hospital_id")
    private Hospital senderHospital;
    @Column(nullable = false, length = 2000) private String body;
    @CreationTimestamp @Column(name = "created_at", updatable = false) private Instant createdAt;
}
