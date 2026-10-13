package com.emergencylink.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import java.time.Instant;

@Entity
@Table(name = "notifications", indexes = @Index(name = "idx_notification_recipient", columnList = "recipient_id, is_read, created_at"))
@Getter @Setter @NoArgsConstructor
public class Notification {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "recipient_id")
    private User recipient;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "referral_id")
    private Referral referral;
    @Column(nullable = false, length = 40) private String type;
    @Column(nullable = false, length = 160) private String title;
    @Column(length = 500) private String message;
    @Column(name = "is_read", nullable = false) private boolean read = false;
    @CreationTimestamp @Column(name = "created_at", updatable = false) private Instant createdAt;
}
