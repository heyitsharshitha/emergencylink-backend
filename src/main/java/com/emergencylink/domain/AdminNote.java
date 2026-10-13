package com.emergencylink.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import java.time.Instant;

/** Internal administrator note on a referral. Never shown to hospitals and kept separate from referral messages. */
@Entity
@Table(name = "admin_notes", indexes = @Index(name = "idx_admin_note_referral", columnList = "referral_id, created_at"))
@Getter @Setter @NoArgsConstructor
public class AdminNote {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "referral_id")
    private Referral referral;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "author_id")
    private User author;
    @Column(name = "author_label", length = 200) private String authorLabel;
    @Column(nullable = false, length = 2000) private String body;
    @CreationTimestamp @Column(name = "created_at", updatable = false) private Instant createdAt;
}
